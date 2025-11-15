// order-service/src/main/java/com/yads/orderservice/service/OrderServiceImpl.java
package com.yads.orderservice.service;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.dto.BatchReserveItem;
import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.common.dto.BatchReserveStockResponse;
import com.yads.common.dto.StoreResponse;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.InsufficientStockException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.common.model.Address;
import com.yads.orderservice.dto.OrderItemRequest;
import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.dto.OrderResponse;
import com.yads.orderservice.exception.ExternalServiceException;
import com.yads.orderservice.exception.InvalidOrderStateException;
import com.yads.orderservice.mapper.OrderMapper;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderItem;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.model.ProductSnapshot;
import com.yads.orderservice.repository.OrderRepository;
import com.yads.orderservice.repository.ProductSnapshotRepository;
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
import java.util.Objects;
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
    private final ProductSnapshotRepository productSnapshotRepository;

    @Override
    @Transactional // Add @transactional
    public OrderResponse createOrder(OrderRequest orderRequest, Jwt jwt) {
        log.info("Order creation process started. Store ID: {}", orderRequest.getStoreId());
        UUID userId = UUID.fromString(jwt.getSubject());

        // --- Step 2: Validate cart using LOCAL ProductSnapshot cache (NO N+1!)
        // CRITICAL FIX: Use product_snapshots table instead of calling store-service N times
        BigDecimal totalPrice = BigDecimal.ZERO;
        List<OrderItem> orderItems = new java.util.ArrayList<>();

        // Collect all product IDs first
        List<UUID> productIds = orderRequest.getItems().stream()
                .map(OrderItemRequest::getProductId)
                .collect(Collectors.toList());

        // SINGLE DATABASE QUERY to fetch all products at once (NO N+1!)
        Map<UUID, ProductSnapshot> productSnapshots = productSnapshotRepository
                .findAllById(productIds).stream()
                .collect(Collectors.toMap(
                        ProductSnapshot::getProductId,
                        snapshot -> snapshot
                ));

        log.info("Fetched {} product snapshots from local cache in a SINGLE query (no N+1!)", productSnapshots.size());

        // Now validate each item using the cached snapshots
        for (OrderItemRequest reqItem : orderRequest.getItems()) {
            ProductSnapshot productSnapshot = productSnapshots.get(reqItem.getProductId());

            // Basic validation: product must exist in our cache
            if (productSnapshot == null) {
                log.warn("Product not found in local cache: productId={}, storeId={}",
                        reqItem.getProductId(), orderRequest.getStoreId());
                throw new ResourceNotFoundException("Product not found: " + reqItem.getProductId());
            }

            // Validate product belongs to the correct store
            if (!productSnapshot.getStoreId().equals(orderRequest.getStoreId())) {
                log.warn("Product belongs to different store: productId={}, expectedStoreId={}, actualStoreId={}",
                        reqItem.getProductId(), orderRequest.getStoreId(), productSnapshot.getStoreId());
                throw new IllegalArgumentException("Product " + reqItem.getProductId() +
                        " does not belong to store " + orderRequest.getStoreId());
            }

            // Validate availability
            if (!productSnapshot.isAvailable()) {
                log.warn("Product not available: productId={}, name={}",
                        reqItem.getProductId(), productSnapshot.getName());
                throw new InvalidOrderStateException("Product is not available: " + productSnapshot.getName());
            }

            // Create order item using cached snapshot data
            OrderItem item = new OrderItem();
            item.setProductId(productSnapshot.getProductId());
            item.setProductName(productSnapshot.getName());
            item.setPrice(productSnapshot.getPrice());
            item.setQuantity(reqItem.getQuantity());

            orderItems.add(item);

            // Calculate total price
            totalPrice = totalPrice.add(productSnapshot.getPrice().multiply(BigDecimal.valueOf(reqItem.getQuantity())));
        }

        log.info("Cart validated using local cache (no stock reserved yet). Total Price: {}", totalPrice);

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
        // Also fetch the store details to snapshot the pickup address
        StoreResponse storeResponse = verifyStoreOwnershipAndGetStore(order, userId, jwt);
        log.info("Store ownership verified: userId={}, storeId={}, storeName={}",
                userId, order.getStoreId(), storeResponse.getName());

        // 6. CRITICAL: Reserve stock for ALL items in a SINGLE batch call
        // FIXED: NO MORE N+1! Single HTTP call with automatic transaction + saga compensation
        // If ANY product fails, store-service rolls back ALL reservations automatically
        log.info("Attempting to reserve stock for order: orderId={}, itemCount={}", orderId, order.getItems().size());

        // Build batch request
        List<BatchReserveItem> batchItems = order.getItems().stream()
                .map(item -> BatchReserveItem.builder()
                        .productId(item.getProductId())
                        .quantity(item.getQuantity())
                        .build())
                .collect(Collectors.toList());

        BatchReserveStockRequest batchRequest = BatchReserveStockRequest.builder()
                .storeId(order.getStoreId())
                .items(batchItems)
                .build();

        try {
            // SINGLE HTTP CALL to reserve all products (no N+1!)
            // Store-service handles this in a single transaction
            // If any item fails, the entire transaction rolls back - perfect saga compensation!
            List<BatchReserveStockResponse> responses = storeServiceWebClient.post()
                    .uri("/api/v1/products/batch-reserve")
                    .header("Authorization", "Bearer " + jwt.getTokenValue())
                    .bodyValue(batchRequest)
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<List<BatchReserveStockResponse>>() {})
                    .block();

            if (responses == null || responses.isEmpty()) {
                throw new ExternalServiceException("Store service returned empty response for batch reservation");
            }

            // Log successful reservations
            for (BatchReserveStockResponse response : responses) {
                log.info("Stock reserved successfully: orderId={}, productId={}, productName={}, quantity={}, remainingStock={}",
                        orderId, response.getProductId(), response.getProductName(),
                        response.getReservedQuantity(), response.getRemainingStock());
            }

            log.info("All stock reservations completed successfully in SINGLE batch call: orderId={}, itemCount={}",
                    orderId, order.getItems().size());

        } catch (WebClientResponseException e) {
            log.error("Batch stock reservation failed: orderId={}, status={}, message={}",
                    orderId, e.getStatusCode(), e.getResponseBodyAsString());

            // SAGA COMPENSATION: Store-service already rolled back the transaction
            // No partial reservations exist - order stays PENDING
            // This is the correct behavior!

            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResourceNotFoundException("One or more products not found during reservation");
            } else if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                // Insufficient stock for one or more items
                throw new InsufficientStockException(
                        "Insufficient stock for one or more products. Cannot accept order. Please cancel it.");
            } else if (e.getStatusCode() == HttpStatus.CONFLICT || e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                String error = e.getResponseBodyAsString();
                throw new IllegalArgumentException("Failed to reserve stock: " + error);
            } else {
                throw new ExternalServiceException("Store service unavailable during stock reservation: " + e.getMessage());
            }
        }

        // 7. All checks complete, update status and snapshot pickup address
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.PREPARING);

        // Snapshot the store's pickup address into the order
        Address pickupAddress = new Address();
        pickupAddress.setStreet(storeResponse.getStreet());
        pickupAddress.setCity(storeResponse.getCity());
        pickupAddress.setState(storeResponse.getState());
        pickupAddress.setPostalCode(storeResponse.getPostalCode());
        pickupAddress.setCountry(storeResponse.getCountry());
        order.setPickupAddress(pickupAddress);

        log.info("Pickup address snapshotted: orderId={}, store={}, address={}, {}, {}",
                orderId, storeResponse.getName(),
                pickupAddress.getStreet(), pickupAddress.getCity(), pickupAddress.getState());

        // TODO: Remove this mock when courier-service is built
        // Temporarily assign a hardcoded courier for testing
        // In production, courier-service will listen to "order.preparing" event and assign a courier
        order.setCourierId(null); // Mock courier UUID

        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, user={}, storeId={}",
                orderId, oldStatus, OrderStatus.PREPARING, userId, order.getStoreId());

        // 8. Send RabbitMQ event
        OrderAssignmentContract contract = OrderAssignmentContract.builder()
                .orderId(updatedOrder.getId())
                .storeId(updatedOrder.getStoreId())
                .pickupAddress(updatedOrder.getPickupAddress())
                .shippingAddress(updatedOrder.getShippingAddress())
                .build();

        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.preparing", contract);
            log.info("'order.preparing' event sent to RabbitMQ. Order ID: {}", orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. Order ID: {}. Error: {}", orderId, e.getMessage());
        }

        OrderResponse orderResponse = orderMapper.toOrderResponse(updatedOrder);
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

        // Store the old status for later use in stock restoration logic
        OrderStatus oldStatus = order.getStatus();

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

        // 5. Stock restoration will be handled ASYNCHRONOUSLY by store-service
        // When store-service receives the 'order.cancelled' event, it will:
        // - Check if the order was PREPARING/ON_THE_WAY (stock needs restoration)
        // - Restore stock for all items in the cancelled order
        // This approach ensures:
        // - Order cancellation succeeds even if store-service is down
        // - Loose coupling between services
        // - Eventual consistency (stock restored when store-service processes the event)
        // - No blocking HTTP calls = better user experience
        if (oldStatus == OrderStatus.PREPARING || oldStatus == OrderStatus.ON_THE_WAY) {
            log.info("Order was accepted (status={}), stock will be restored asynchronously by store-service: orderId={}, itemCount={}",
                    oldStatus, orderId, order.getItems().size());
        } else {
            log.info("Order was PENDING, no stock restoration needed: orderId={}, status={}", orderId, oldStatus);
        }

        // 6. All checks complete, update status
        order.setStatus(OrderStatus.CANCELLED);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, cancelledBy={}",
                orderId, oldStatus, OrderStatus.CANCELLED, userId);

        // 7. Send RabbitMQ event with OrderCancelledContract
        // CRITICAL: Include oldStatus to prevent GHOST INVENTORY
        // Store-service will only restore stock if oldStatus was PREPARING or ON_THE_WAY
        List<BatchReserveItem> itemsToRestore = updatedOrder.getItems().stream()
                .map(item -> BatchReserveItem.builder()
                        .productId(item.getProductId())
                        .quantity(item.getQuantity())
                        .build())
                .collect(Collectors.toList());

        OrderCancelledContract contract = OrderCancelledContract.builder()
                .orderId(updatedOrder.getId())
                .storeId(updatedOrder.getStoreId())
                .oldStatus(oldStatus.name()) // CRITICAL: Prevents ghost inventory
                .items(itemsToRestore)
                .build();

        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.cancelled", contract);
            log.info("'order.cancelled' event sent to RabbitMQ with oldStatus={}: orderId={}",
                    oldStatus.name(), orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. Order ID: {}. Error: {}", orderId, e.getMessage());
        }

        // Return normal OrderResponse to user
        return orderMapper.toOrderResponse(updatedOrder);
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
     * Returns the StoreResponse if ownership is verified, throws AccessDeniedException otherwise.
     *
     * @param order the order containing the storeId
     * @param userId the user ID to verify
     * @param jwt the JWT token for authentication
     * @return StoreResponse object if the user owns the store
     * @throws AccessDeniedException if the user is not the store owner
     * @throws ExternalServiceException if store service communication fails
     */
    private StoreResponse verifyStoreOwnershipAndGetStore(Order order, UUID userId, Jwt jwt) {
        try {
            StoreResponse storeResponse = storeServiceWebClient.get()
                    .uri("/api/v1/stores/" + order.getStoreId())
                    .header("Authorization", "Bearer " + jwt.getTokenValue())
                    .retrieve()
                    .bodyToMono(StoreResponse.class)
                    .block();

            // Handle null response from store service
            if (storeResponse == null) {
                log.error("Store service returned null response for storeId={}", order.getStoreId());
                throw new ExternalServiceException("Store service communication failed: received null response");
            }

            // Perform null-safe owner check
            if (!Objects.equals(storeResponse.getOwnerId(), userId)) {
                throw new AccessDeniedException("Access Denied: You are not the owner of this store");
            }

            return storeResponse;
        } catch (WebClientResponseException e) {
            log.error("Store service communication failed: storeId={}, status={}, message={}",
                    order.getStoreId(), e.getStatusCode(), e.getMessage());
            throw new ExternalServiceException("Cannot verify store ownership: " + e.getMessage());
        }
    }

    /**
     * Helper method to check if a user is the owner of the store associated with an order.
     * Returns true if the user owns the store, false otherwise.
     * This is a convenience wrapper around verifyStoreOwnershipAndGetStore for cases
     * where we only need a boolean check.
     *
     * @param order the order containing the storeId
     * @param userId the user ID to verify
     * @param jwt the JWT token for authentication
     * @return true if the user owns the store, false otherwise
     * @throws ExternalServiceException if store service communication fails
     */
    private boolean isStoreOwnerOfOrder(Order order, UUID userId, Jwt jwt) {
        try {
            verifyStoreOwnershipAndGetStore(order, userId, jwt);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    @Override
    @Transactional
    public void assignCourierToOrder(UUID orderId, UUID courierId) {
        log.info("Assigning courier to order: orderId={}, courierId={}", orderId, courierId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        order.setCourierId(courierId);
        orderRepository.save(order);

        log.info("Courier assigned successfully: orderId={}, courierId={}", orderId, courierId);
    }
}