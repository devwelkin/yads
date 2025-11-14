// order-service/src/main/java/com/yads/orderservice/service/OrderServiceImpl.java
package com.yads.orderservice.service;

import com.yads.common.dto.ReserveStockRequest;
import com.yads.common.dto.StoreResponse;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.orderservice.dto.OrderItemRequest;
import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.dto.OrderResponse;
import com.yads.orderservice.dto.ProductSnapshotDto;
import com.yads.orderservice.exception.ExternalServiceException;
import com.yads.orderservice.exception.InvalidOrderStateException;
import com.yads.orderservice.mapper.OrderMapper;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderItem;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final RabbitTemplate rabbitTemplate;
    private final WebClient storeServiceWebClient;

    @Override
    @Transactional // Add @transactional
    public OrderResponse createOrder(OrderRequest orderRequest, Jwt jwt) {
        log.info("Order creation process started. Store ID: {}", orderRequest.getStoreId());
        UUID userId = UUID.fromString(jwt.getSubject());


        // --- Step 2: Validate cart
        BigDecimal totalPrice = BigDecimal.ZERO;
        List<OrderItem> orderItems = new java.util.ArrayList<>();

        for (OrderItemRequest reqItem : orderRequest.getItems()) {

            try {
                ReserveStockRequest stockRequest = ReserveStockRequest.builder()
                        .quantity(reqItem.getQuantity())
                        .storeId(orderRequest.getStoreId())
                        .build();

                ProductSnapshotDto productInfo = storeServiceWebClient.post()
                        .uri("/api/v1/products/" + reqItem.getProductId() + "/reserve")
                        .header("Authorization", "Bearer " + jwt.getTokenValue())
                        .bodyValue(stockRequest)
                        .retrieve()
                        .bodyToMono(ProductSnapshotDto.class)
                        .block();

                // Create order item
                OrderItem item = new OrderItem();
                item.setProductId(productInfo.getId());
                item.setProductName(productInfo.getName());
                item.setPrice(productInfo.getPrice());
                item.setQuantity(reqItem.getQuantity());

                orderItems.add(item);

                // Calculate total price
                totalPrice = totalPrice.add(productInfo.getPrice().multiply(BigDecimal.valueOf(reqItem.getQuantity())));
            } catch (WebClientResponseException e) {
                log.error("Stock reservation failed: productId={}, storeId={}, status={}, message={}",
                        reqItem.getProductId(), orderRequest.getStoreId(),
                        e.getStatusCode(), e.getResponseBodyAsString());

                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    throw new ResourceNotFoundException("Product not found: " + reqItem.getProductId());
                } else if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                    throw new InvalidOrderStateException("Insufficient stock for product: " + reqItem.getProductId());
                } else if (e.getStatusCode() == HttpStatus.CONFLICT || e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                    String error = e.getResponseBodyAsString();
                    throw new IllegalArgumentException(error);
                } else {
                    throw new ExternalServiceException("Store service unavailable: " + e.getMessage());
                }
            }

        }
        log.info("Cart validated locally. Total Price: {}", totalPrice);

        // --- Step 3: Save order
        Order order = new Order();
        order.setUserId(userId);
        order.setStoreId(orderRequest.getStoreId());
        order.setShippingAddress(orderRequest.getShippingAddress());
        order.setStatus(OrderStatus.PENDING);
        order.setTotalPrice(totalPrice);

        orderItems.forEach(item -> item.setOrder(order));
        order.setItems(orderItems);

        Order savedOrder = orderRepository.save(order);
        log.info("Order saved to database. ID: {}", savedOrder.getId());

        // --- Step 4: rabbitmq event
        OrderResponse orderResponse = orderMapper.toOrderResponse(savedOrder);
        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.created", orderResponse);
            log.info("'order.created' event sent to RabbitMQ. order id: {}", savedOrder.getId());
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. order id: {}. error: {}", savedOrder.getId(), e.getMessage());
        }

        return orderResponse;
    }

    @Override
    public List<OrderResponse> getMyOrders(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<Order> orders = orderRepository.findByUserId(userId);

        return orders.stream()
                .map(orderMapper::toOrderResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse acceptOrder(UUID orderId, Jwt jwt) {
        log.info("Accept order process started. Order ID: {}", orderId);

        // 1. Get order from DB
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        // 2. Check order status
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Invalid state transition: orderId={}, currentStatus={}, attemptedAction=accept",
                    orderId, order.getStatus());
            throw new InvalidOrderStateException("Order status must be PENDING to accept. Current status: " + order.getStatus());
        }

        // 3. Get userId and role from JWT
        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = extractClientRoles(jwt);

        // 4. Role check - must be STORE_OWNER
        if (!roles.contains("STORE_OWNER")) {
            log.warn("Access denied: User {} attempted to accept order {} without STORE_OWNER role",
                    userId, orderId);
            throw new AccessDeniedException("Access Denied: Only store owners can accept orders");
        }

        // 5. Critical: Is this store owner the owner of this order's store?
        if (!isStoreOwnerOfOrder(order, userId, jwt)) {
            log.warn("Access denied: User {} attempted to accept order {} for store {} which they don't own",
                    userId, orderId, order.getStoreId());
            throw new AccessDeniedException("Access Denied: You are not the owner of this store");
        }

        // 6. All checks complete, update status
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.PREPARING);

        // TODO: Remove this mock when courier-service is built
        // Temporarily assign a hardcoded courier for testing
        // In production, courier-service will listen to "order.preparing" event and assign a courier
        order.setCourierId(UUID.fromString("40869d03-c4a2-41e7-8363-b3e4b81004df")); // Mock courier UUID

        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, user={}, storeId={}",
                orderId, oldStatus, OrderStatus.PREPARING, userId, order.getStoreId());

        // 7. Send RabbitMQ event
        OrderResponse orderResponse = orderMapper.toOrderResponse(updatedOrder);
        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.preparing", orderResponse);
            log.info("'order.preparing' event sent to RabbitMQ. Order ID: {}", orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. Order ID: {}. Error: {}", orderId, e.getMessage());
        }

        return orderResponse;
    }

    @Override
    @Transactional
    public OrderResponse pickupOrder(UUID orderId, Jwt jwt) {
        log.info("Pickup order process started. Order ID: {}", orderId);

        // 1. Get order from DB
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        // 2. Check order status - must be PREPARING
        if (order.getStatus() != OrderStatus.PREPARING) {
            log.warn("Invalid state transition: orderId={}, currentStatus={}, attemptedAction=pickup",
                    orderId, order.getStatus());
            throw new InvalidOrderStateException("Order status must be PREPARING to pickup. Current status: " + order.getStatus());
        }

        // 3. Get userId and role from JWT
        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = extractClientRoles(jwt);

        // 4. Role check - must be COURIER
        if (!roles.contains("COURIER")) {
            log.warn("Access denied: User {} attempted to pickup order {} without COURIER role",
                    userId, orderId);
            throw new AccessDeniedException("Access Denied: Only couriers can pickup orders");
        }

        // 5. Critical: Is this courier assigned to this order?
        if (order.getCourierId() == null) {
            log.warn("Courier assignment missing: orderId={}, attemptedBy={}", orderId, userId);
            throw new InvalidOrderStateException("No courier has been assigned to this order yet.");
        }

        if (!order.getCourierId().equals(userId)) {
            log.warn("Access denied: Courier {} attempted to pickup order {} assigned to courier {}",
                    userId, orderId, order.getCourierId());
            throw new AccessDeniedException("Access Denied: You are not assigned to this order");
        }

        // 6. All checks complete, update status
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.ON_THE_WAY);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, courier={}",
                orderId, oldStatus, OrderStatus.ON_THE_WAY, userId);

        // 7. Send RabbitMQ event
        OrderResponse orderResponse = orderMapper.toOrderResponse(updatedOrder);
        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.on_the_way", orderResponse);
            log.info("'order.on_the_way' event sent to RabbitMQ. Order ID: {}", orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. Order ID: {}. Error: {}", orderId, e.getMessage());
        }

        return orderResponse;
    }

    @Override
    @Transactional
    public OrderResponse deliverOrder(UUID orderId, Jwt jwt) {
        log.info("Deliver order process started. Order ID: {}", orderId);

        // 1. Get order from DB
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        // 2. Check order status - must be ON_THE_WAY
        if (order.getStatus() != OrderStatus.ON_THE_WAY) {
            log.warn("Invalid state transition: orderId={}, currentStatus={}, attemptedAction=deliver",
                    orderId, order.getStatus());
            throw new InvalidOrderStateException("Order status must be ON_THE_WAY to deliver. Current status: " + order.getStatus());
        }

        // 3. Get userId and role from JWT
        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = extractClientRoles(jwt);

        // 4. Role check - must be COURIER
        if (!roles.contains("COURIER")) {
            log.warn("Access denied: User {} attempted to deliver order {} without COURIER role",
                    userId, orderId);
            throw new AccessDeniedException("Access Denied: Only couriers can deliver orders");
        }

        // 5. Critical: Is this courier assigned to this order?
        if (order.getCourierId() == null) {
            log.warn("Courier assignment missing: orderId={}, attemptedBy={}", orderId, userId);
            throw new InvalidOrderStateException("No courier has been assigned to this order yet.");
        }

        if (!order.getCourierId().equals(userId)) {
            log.warn("Access denied: Courier {} attempted to deliver order {} assigned to courier {}",
                    userId, orderId, order.getCourierId());
            throw new AccessDeniedException("Access Denied: You are not assigned to this order");
        }

        // 6. All checks complete, update status
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.DELIVERED);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, courier={}",
                orderId, oldStatus, OrderStatus.DELIVERED, userId);

        // 7. Send RabbitMQ event
        OrderResponse orderResponse = orderMapper.toOrderResponse(updatedOrder);
        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.delivered", orderResponse);
            log.info("'order.delivered' event sent to RabbitMQ. Order ID: {}", orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. Order ID: {}. Error: {}", orderId, e.getMessage());
        }

        return orderResponse;
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(UUID orderId, Jwt jwt) {
        log.info("Cancel order process started. Order ID: {}", orderId);

        // 1. Get order from DB
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        // 2. Check if order is already in a final state
        if (order.getStatus() == OrderStatus.CANCELLED) {
            log.warn("Invalid state transition: orderId={}, currentStatus=CANCELLED, attemptedAction=cancel",
                    orderId);
            throw new InvalidOrderStateException("Order is already cancelled.");
        }
        if (order.getStatus() == OrderStatus.DELIVERED) {
            log.warn("Invalid state transition: orderId={}, currentStatus=DELIVERED, attemptedAction=cancel",
                    orderId);
            throw new InvalidOrderStateException("Cannot cancel a delivered order.");
        }

        // 3. Get userId and roles from JWT
        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = extractClientRoles(jwt);

        // 4. Status-dependent authorization logic
        if (order.getStatus() == OrderStatus.PENDING) {
            // PENDING: Both customer and store_owner can cancel
            boolean isCustomer = order.getUserId().equals(userId);
            boolean isStoreOwner = (roles.contains("STORE_OWNER") && isStoreOwnerOfOrder(order, userId, jwt));

            if (!isCustomer && !isStoreOwner) {
                log.warn("Access denied: User {} attempted to cancel PENDING order {} (owner: {}, storeId: {})",
                        userId, orderId, order.getUserId(), order.getStoreId());
                throw new AccessDeniedException("Access Denied: Only the customer or store owner can cancel a pending order");
            }
            log.info("Order cancellation authorized: orderId={}, status=PENDING, cancelledBy={}, isCustomer={}, isStoreOwner={}",
                    orderId, userId, isCustomer, isStoreOwner);

        } else if (order.getStatus() == OrderStatus.PREPARING) {
            // PREPARING: Only store_owner can cancel (customer's window has closed)
            if (!roles.contains("STORE_OWNER") || !isStoreOwnerOfOrder(order, userId, jwt)) {
                log.warn("Access denied: User {} attempted to cancel PREPARING order {} (storeId: {})",
                        userId, orderId, order.getStoreId());
                throw new AccessDeniedException("Access Denied: Only the store owner can cancel an order that is being prepared");
            }
            log.info("Order cancellation authorized: orderId={}, status=PREPARING, cancelledBy={} (store owner)",
                    orderId, userId);

        } else if (order.getStatus() == OrderStatus.ON_THE_WAY) {
            // ON_THE_WAY: Nobody can cancel (too late - courier already has it)
            log.warn("Invalid state transition: orderId={}, currentStatus=ON_THE_WAY, attemptedAction=cancel, attemptedBy={}",
                    orderId, userId);
            throw new InvalidOrderStateException("Cannot cancel an order that is already on the way.");
        }

        // 5. All checks complete, update status
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, cancelledBy={}",
                orderId, oldStatus, OrderStatus.CANCELLED, userId);

        // 6. Send RabbitMQ event
        OrderResponse orderResponse = orderMapper.toOrderResponse(updatedOrder);
        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.cancelled", orderResponse);
            log.info("'order.cancelled' event sent to RabbitMQ. Order ID: {}", orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. Order ID: {}. Error: {}", orderId, e.getMessage());
        }

        return orderResponse;
    }

    @Override
    public OrderResponse getOrderById(UUID orderId, Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        if (!order.getUserId().equals(userId)) {
            log.warn("Access denied: User {} attempted to access order {} owned by user {}",
                    userId, orderId, order.getUserId());
            throw new AccessDeniedException("Access Denied: You are not authorized to view this order");
        }

        return orderMapper.toOrderResponse(order);
    }

    /**
     * Helper method to extract client-specific roles from JWT.
     * Keycloak stores client roles in: resource_access.{client-id}.roles
     *
     * @param jwt the JWT token
     * @return list of roles for yads-backend client, or empty list if not found
     */
    private List<String> extractClientRoles(Jwt jwt) {
        try {
            Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
            if (resourceAccess == null) {
                return List.of();
            }

            Map<String, Object> yadsBackend = (Map<String, Object>) resourceAccess.get("yads-backend");
            if (yadsBackend == null) {
                return List.of();
            }

            List<String> roles = (List<String>) yadsBackend.get("roles");
            return roles != null ? roles : List.of();
        } catch (Exception e) {
            log.error("Error extracting client roles from JWT: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Helper method to verify if a user is the owner of the store associated with an order.
     *
     * @param order the order containing the storeId
     * @param userId the user ID to verify
     * @param jwt the JWT token for authentication
     * @return true if the user owns the store, false otherwise
     */
    private boolean isStoreOwnerOfOrder(Order order, UUID userId, Jwt jwt) {
        try {
            StoreResponse storeResponse = storeServiceWebClient.get()
                    .uri("/api/v1/stores/" + order.getStoreId())
                    .header("Authorization", "Bearer " + jwt.getTokenValue())
                    .retrieve()
                    .bodyToMono(StoreResponse.class)
                    .block();

            return (storeResponse != null && storeResponse.getOwnerId().equals(userId));
        } catch (WebClientResponseException e) {
            log.error("Store service communication failed: storeId={}, status={}, message={}",
                    order.getStoreId(), e.getStatusCode(), e.getMessage());
            throw new ExternalServiceException("Cannot verify store ownership: " + e.getMessage());
        }
    }
}