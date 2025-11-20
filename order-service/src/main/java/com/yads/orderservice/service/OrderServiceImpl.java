// order-service/src/main/java/com/yads/orderservice/service/OrderServiceImpl.java
package com.yads.orderservice.service;

import com.yads.common.contracts.OrderStatusChangeContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.contracts.OrderAssignedContract;
import com.yads.common.contracts.StockReservationRequestContract;
import com.yads.common.dto.BatchReserveItem;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.orderservice.dto.OrderItemRequest;
import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.dto.OrderResponse;
import com.yads.orderservice.exception.InvalidOrderStateException;
import com.yads.orderservice.mapper.OrderMapper;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderItem;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.model.ProductSnapshot;
import com.yads.orderservice.model.OutboxEvent;
import com.yads.orderservice.repository.OrderRepository;
import com.yads.orderservice.repository.ProductSnapshotRepository;
import com.yads.orderservice.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final ProductSnapshotRepository productSnapshotRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest orderRequest, Jwt jwt) {
        log.info("Order creation process started. Store ID: {}", orderRequest.getStoreId());
        UUID userId = UUID.fromString(jwt.getSubject());

        BigDecimal totalPrice = BigDecimal.ZERO;
        List<OrderItem> orderItems = new java.util.ArrayList<>();

        List<UUID> productIds = orderRequest.getItems().stream()
                .map(OrderItemRequest::getProductId)
                .collect(Collectors.toList());

        // Fetch all products in single query to avoid N+1
        Map<UUID, ProductSnapshot> productSnapshots = productSnapshotRepository
                .findAllById(productIds).stream()
                .collect(Collectors.toMap(
                        ProductSnapshot::getProductId,
                        snapshot -> snapshot));

        log.info("Fetched {} product snapshots from local cache", productSnapshots.size());

        for (OrderItemRequest reqItem : orderRequest.getItems()) {
            ProductSnapshot productSnapshot = productSnapshots.get(reqItem.getProductId());

            if (productSnapshot == null) {
                log.warn("Product not found in local cache: productId={}, storeId={}",
                        reqItem.getProductId(), orderRequest.getStoreId());
                throw new ResourceNotFoundException("Product not found: " + reqItem.getProductId());
            }

            if (!productSnapshot.getStoreId().equals(orderRequest.getStoreId())) {
                log.warn("Product belongs to different store: productId={}, expectedStoreId={}, actualStoreId={}",
                        reqItem.getProductId(), orderRequest.getStoreId(), productSnapshot.getStoreId());
                throw new IllegalArgumentException("Product " + reqItem.getProductId() +
                        " does not belong to store " + orderRequest.getStoreId());
            }

            if (!productSnapshot.isAvailable()) {
                log.warn("Product not available: productId={}, name={}",
                        reqItem.getProductId(), productSnapshot.getName());
                throw new InvalidOrderStateException("Product is not available: " + productSnapshot.getName());
            }

            OrderItem item = new OrderItem();
            item.setProductId(productSnapshot.getProductId());
            item.setProductName(productSnapshot.getName());
            item.setPrice(productSnapshot.getPrice());
            item.setQuantity(reqItem.getQuantity());

            orderItems.add(item);
            totalPrice = totalPrice.add(productSnapshot.getPrice().multiply(BigDecimal.valueOf(reqItem.getQuantity())));
        }

        log.info("Cart validated. Total Price: {}", totalPrice);

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

        OrderResponse orderResponse = orderMapper.toOrderResponse(savedOrder);

        // Publish order.created event as contract for notification service
        try {
            OrderStatusChangeContract contract = OrderStatusChangeContract
                    .builder()
                    .orderId(savedOrder.getId())
                    .userId(savedOrder.getUserId())
                    .storeId(savedOrder.getStoreId())
                    .courierId(savedOrder.getCourierId())
                    .status(savedOrder.getStatus().name())
                    .totalPrice(savedOrder.getTotalPrice())
                    .shippingAddress(savedOrder.getShippingAddress())
                    .pickupAddress(savedOrder.getPickupAddress())
                    .createdAt(savedOrder.getCreatedAt())
                    .build();

            saveOutboxEvent(savedOrder.getId().toString(), "order.created", contract);
            log.info("'order.created' event saved to Outbox. order id: {}", savedOrder.getId());
        } catch (Exception e) {
            log.error("ERROR occurred while saving event to Outbox. order id: {}. error: {}", savedOrder.getId(),
                    e.getMessage());
            throw new RuntimeException("Failed to save outbox event", e);
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

    /**
     * Accepts an order by initiating an async stock reservation saga.
     *
     * ZERO SYNCHRONOUS CALLS - Fully async and decoupled:
     * 1. Verifies store ownership via JWT claim (NO network call!)
     * 2. Updates order status to RESERVING_STOCK
     * 3. Publishes stock reservation request event
     * 4. Returns immediately (non-blocking)
     *
     * The saga continues in StockReplySubscriber when store-service responds.
     *
     * JWT Setup Required:
     * - Store owners must have 'store_id' custom claim in their JWT
     * - See extractStoreId() method for Keycloak configuration
     */
    @Override
    @Transactional // Only for order-service's own database
    public OrderResponse acceptOrder(UUID orderId, Jwt jwt) {
        log.info("Accept order process started. Order ID: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Invalid state transition: orderId={}, currentStatus={}, attemptedAction=accept",
                    orderId, order.getStatus());
            throw new InvalidOrderStateException(
                    "Order status must be PENDING to accept. Current status: " + order.getStatus());
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = extractClientRoles(jwt);

        if (!roles.contains("STORE_OWNER")) {
            log.warn("Access denied: User {} attempted to accept order {} without STORE_OWNER role",
                    userId, orderId);
            throw new AccessDeniedException("Access Denied: Only store owners can accept orders");
        }

        // Verify store ownership via JWT claim (ZERO network calls!)
        UUID storeIdFromJwt = extractStoreId(jwt);
        if (storeIdFromJwt == null) {
            log.error("Store owner JWT missing 'store_id' claim: userId={}", userId);
            throw new AccessDeniedException(
                    "Access Denied: Store ID not found in token. Please contact administrator.");
        }

        if (!storeIdFromJwt.equals(order.getStoreId())) {
            log.warn("Access denied: Store owner {} attempted to accept order for different store. " +
                    "JWT storeId={}, Order storeId={}", userId, storeIdFromJwt, order.getStoreId());
            throw new AccessDeniedException("Access Denied: You are not the owner of this store");
        }

        log.info("Store ownership verified via JWT: userId={}, storeId={}", userId, storeIdFromJwt);

        // 1. Update order status to RESERVING_STOCK
        order.setStatus(OrderStatus.RESERVING_STOCK);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated to RESERVING_STOCK: orderId={}", orderId);

        // 2. Build and publish stock reservation request event
        // Note: pickupAddress will be added by store-service when it responds
        List<BatchReserveItem> batchItems = order.getItems().stream()
                .map(item -> BatchReserveItem.builder()
                        .productId(item.getProductId())
                        .quantity(item.getQuantity())
                        .build())
                .collect(Collectors.toList());

        StockReservationRequestContract contract = StockReservationRequestContract
                .builder()
                .orderId(order.getId())
                .storeId(order.getStoreId())
                .userId(order.getUserId())
                .items(batchItems)
                .pickupAddress(null) // Will be filled by store-service
                .shippingAddress(order.getShippingAddress())
                .build();

        try {
            saveOutboxEvent(order.getId().toString(), "order.stock_reservation.requested", contract);
            log.info("'order.stock_reservation.requested' event saved to Outbox. orderId={}", orderId);
        } catch (Exception e) {
            log.error("Failed to save stock reservation request event. orderId={}. error: {}", orderId, e.getMessage());
            throw new RuntimeException("Failed to save outbox event", e);
        }

        return orderMapper.toOrderResponse(updatedOrder);
    }

    // REMOVED: This method is no longer used after migrating to async saga pattern
    // Order status is now updated in two places:
    // 1. acceptOrder() sets status to RESERVING_STOCK
    // 2. StockReplySubscriber.handleStockReserved() sets status to PREPARING

    @Override
    @Transactional
    public OrderResponse pickupOrder(UUID orderId, Jwt jwt) {
        log.info("Pickup order process started. Order ID: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        if (order.getStatus() != OrderStatus.PREPARING) {
            log.warn("Invalid state transition: orderId={}, currentStatus={}, attemptedAction=pickup",
                    orderId, order.getStatus());
            throw new InvalidOrderStateException(
                    "Order status must be PREPARING to pickup. Current status: " + order.getStatus());
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = extractClientRoles(jwt);

        if (!roles.contains("COURIER")) {
            log.warn("Access denied: User {} attempted to pickup order {} without COURIER role",
                    userId, orderId);
            throw new AccessDeniedException("Access Denied: Only couriers can pickup orders");
        }

        if (order.getCourierId() == null) {
            log.warn("Courier assignment missing: orderId={}, attemptedBy={}", orderId, userId);
            throw new InvalidOrderStateException("No courier has been assigned to this order yet.");
        }

        if (!order.getCourierId().equals(userId)) {
            log.warn("Access denied: Courier {} attempted to pickup order {} assigned to courier {}",
                    userId, orderId, order.getCourierId());
            throw new AccessDeniedException("Access Denied: You are not assigned to this order");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.ON_THE_WAY);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, courier={}",
                orderId, oldStatus, OrderStatus.ON_THE_WAY, userId);

        OrderResponse orderResponse = orderMapper.toOrderResponse(updatedOrder);

        // Publish order.on_the_way event as contract for notification service
        try {
            OrderStatusChangeContract contract = OrderStatusChangeContract
                    .builder()
                    .orderId(updatedOrder.getId())
                    .userId(updatedOrder.getUserId())
                    .storeId(updatedOrder.getStoreId())
                    .courierId(updatedOrder.getCourierId())
                    .status(updatedOrder.getStatus().name())
                    .totalPrice(updatedOrder.getTotalPrice())
                    .shippingAddress(updatedOrder.getShippingAddress())
                    .pickupAddress(updatedOrder.getPickupAddress())
                    .createdAt(updatedOrder.getCreatedAt())
                    .build();

            saveOutboxEvent(updatedOrder.getId().toString(), "order.on_the_way", contract);
            log.info("'order.on_the_way' event saved to Outbox. Order ID: {}", orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while saving event to Outbox. Order ID: {}. Error: {}", orderId,
                    e.getMessage());
            throw new RuntimeException("Failed to save outbox event", e);
        }

        return orderResponse;
    }

    @Override
    @Transactional
    public OrderResponse deliverOrder(UUID orderId, Jwt jwt) {
        log.info("Deliver order process started. Order ID: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        if (order.getStatus() != OrderStatus.ON_THE_WAY) {
            log.warn("Invalid state transition: orderId={}, currentStatus={}, attemptedAction=deliver",
                    orderId, order.getStatus());
            throw new InvalidOrderStateException(
                    "Order status must be ON_THE_WAY to deliver. Current status: " + order.getStatus());
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = extractClientRoles(jwt);

        if (!roles.contains("COURIER")) {
            log.warn("Access denied: User {} attempted to deliver order {} without COURIER role",
                    userId, orderId);
            throw new AccessDeniedException("Access Denied: Only couriers can deliver orders");
        }

        if (order.getCourierId() == null) {
            log.warn("Courier assignment missing: orderId={}, attemptedBy={}", orderId, userId);
            throw new InvalidOrderStateException("No courier has been assigned to this order yet.");
        }

        if (!order.getCourierId().equals(userId)) {
            log.warn("Access denied: Courier {} attempted to deliver order {} assigned to courier {}",
                    userId, orderId, order.getCourierId());
            throw new AccessDeniedException("Access Denied: You are not assigned to this order");
        }

        OrderStatus oldStatus = order.getStatus();
        order.setStatus(OrderStatus.DELIVERED);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, courier={}",
                orderId, oldStatus, OrderStatus.DELIVERED, userId);

        OrderResponse orderResponse = orderMapper.toOrderResponse(updatedOrder);

        // Publish order.delivered event as contract for notification service
        try {
            OrderStatusChangeContract contract = OrderStatusChangeContract
                    .builder()
                    .orderId(updatedOrder.getId())
                    .userId(updatedOrder.getUserId())
                    .storeId(updatedOrder.getStoreId())
                    .courierId(updatedOrder.getCourierId())
                    .status(updatedOrder.getStatus().name())
                    .totalPrice(updatedOrder.getTotalPrice())
                    .shippingAddress(updatedOrder.getShippingAddress())
                    .pickupAddress(updatedOrder.getPickupAddress())
                    .createdAt(updatedOrder.getCreatedAt())
                    .build();

            saveOutboxEvent(updatedOrder.getId().toString(), "order.delivered", contract);
            log.info("'order.delivered' event saved to Outbox. Order ID: {}", orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while saving event to Outbox. Order ID: {}. Error: {}", orderId,
                    e.getMessage());
            throw new RuntimeException("Failed to save outbox event", e);
        }

        return orderResponse;
    }

    /**
     * Cancels an order with status-dependent authorization.
     * Store ownership verification performed OUTSIDE of database transaction.
     */
    @Override
    public OrderResponse cancelOrder(UUID orderId, Jwt jwt) {
        log.info("Cancel order process started. Order ID: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

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
        if (order.getStatus() == OrderStatus.RESERVING_STOCK) {
            log.warn("Invalid state transition: orderId={}, currentStatus=RESERVING_STOCK, attemptedAction=cancel",
                    orderId);
            throw new InvalidOrderStateException("Order is currently being processed. Please try again shortly.");
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = extractClientRoles(jwt);
        OrderStatus oldStatus = order.getStatus();

        // Authorization logic using JWT claims (ZERO network calls!)
        if (order.getStatus() == OrderStatus.PENDING) {
            boolean isCustomer = order.getUserId().equals(userId);
            boolean isStoreOwner = false;

            if (roles.contains("STORE_OWNER")) {
                UUID storeIdFromJwt = extractStoreId(jwt);
                isStoreOwner = storeIdFromJwt != null && storeIdFromJwt.equals(order.getStoreId());
            }

            if (!isCustomer && !isStoreOwner) {
                log.warn("Access denied: User {} attempted to cancel PENDING order {} (owner: {}, storeId: {})",
                        userId, orderId, order.getUserId(), order.getStoreId());
                throw new AccessDeniedException(
                        "Access Denied: Only the customer or store owner can cancel a pending order");
            }
            log.info(
                    "Order cancellation authorized: orderId={}, status=PENDING, cancelledBy={}, isCustomer={}, isStoreOwner={}",
                    orderId, userId, isCustomer, isStoreOwner);

        } else if (order.getStatus() == OrderStatus.PREPARING) {
            if (!roles.contains("STORE_OWNER")) {
                log.warn("Access denied: User {} attempted to cancel PREPARING order {} without STORE_OWNER role",
                        userId, orderId);
                throw new AccessDeniedException(
                        "Access Denied: Only the store owner can cancel an order that is being prepared");
            }

            UUID storeIdFromJwt = extractStoreId(jwt);
            if (storeIdFromJwt == null || !storeIdFromJwt.equals(order.getStoreId())) {
                log.warn(
                        "Access denied: User {} attempted to cancel PREPARING order {} for different store. JWT storeId={}, Order storeId={}",
                        userId, orderId, storeIdFromJwt, order.getStoreId());
                throw new AccessDeniedException("Access Denied: You are not the owner of this store");
            }

            log.info("Order cancellation authorized: orderId={}, status=PREPARING, cancelledBy={} (store owner)",
                    orderId, userId);

        } else if (order.getStatus() == OrderStatus.ON_THE_WAY) {
            log.warn(
                    "Invalid state transition: orderId={}, currentStatus=ON_THE_WAY, attemptedAction=cancel, attemptedBy={}",
                    orderId, userId);
            throw new InvalidOrderStateException("Cannot cancel an order that is already on the way.");
        }

        return performOrderCancellation(orderId, oldStatus, userId);
    }

    @Transactional
    private OrderResponse performOrderCancellation(UUID orderId, OrderStatus oldStatus, UUID userId) {
        log.info("Performing order cancellation: orderId={}", orderId);

        // Re-validate status to prevent race conditions
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException("Order is already cancelled.");
        }
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new InvalidOrderStateException("Cannot cancel a delivered order.");
        }
        if (order.getStatus() == OrderStatus.RESERVING_STOCK) {
            throw new InvalidOrderStateException("Order is currently being processed. Please try again shortly.");
        }

        // Stock restoration handled asynchronously by store-service via RabbitMQ event
        if (oldStatus == OrderStatus.PREPARING || oldStatus == OrderStatus.ON_THE_WAY) {
            log.info("Order was accepted (status={}), stock will be restored asynchronously: orderId={}, itemCount={}",
                    oldStatus, orderId, order.getItems().size());
        } else {
            log.info("Order was PENDING, no stock restoration needed: orderId={}, status={}", orderId, oldStatus);
        }

        order.setStatus(OrderStatus.CANCELLED);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated: orderId={}, from={}, to={}, cancelledBy={}",
                orderId, oldStatus, OrderStatus.CANCELLED, userId);

        // Include oldStatus to prevent ghost inventory
        List<BatchReserveItem> itemsToRestore = updatedOrder.getItems().stream()
                .map(item -> BatchReserveItem.builder()
                        .productId(item.getProductId())
                        .quantity(item.getQuantity())
                        .build())
                .collect(Collectors.toList());

        OrderCancelledContract contract = OrderCancelledContract.builder()
                .orderId(updatedOrder.getId())
                .storeId(updatedOrder.getStoreId())
                .userId(updatedOrder.getUserId())
                .courierId(updatedOrder.getCourierId()) // nullable
                .oldStatus(oldStatus.name())
                .items(itemsToRestore)
                .build();

        try {
            saveOutboxEvent(updatedOrder.getId().toString(), "order.cancelled", contract);
            log.info("'order.cancelled' event saved to Outbox with oldStatus={}: orderId={}",
                    oldStatus.name(), orderId);
        } catch (Exception e) {
            log.error("ERROR occurred while saving event to Outbox. Order ID: {}. Error: {}", orderId,
                    e.getMessage());
            throw new RuntimeException("Failed to save outbox event", e);
        }

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

    private List<String> extractClientRoles(Jwt jwt) {
        return Optional.ofNullable(jwt.getClaim("resource_access"))
                .filter(Map.class::isInstance)
                .map(claim -> (Map<?, ?>) claim)
                .map(accessMap -> accessMap.get("yads-backend"))
                .filter(Map.class::isInstance)
                .map(backend -> (Map<?, ?>) backend)
                .map(backendMap -> backendMap.get("roles"))
                .filter(List.class::isInstance)
                .map(roles -> (List<?>) roles)
                .map(list -> list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Extracts store_id custom claim from JWT.
     * This claim should be added by Keycloak when a store owner logs in.
     *
     * Setup in Keycloak:
     * 1. Clients → yads-backend → Client Scopes → yads-backend-dedicated
     * 2. Add Mapper → "store_id" (User Attribute)
     * 3. User Attribute: store_id
     * 4. Token Claim Name: store_id
     * 5. Claim JSON Type: String
     */
    private UUID extractStoreId(Jwt jwt) {
        try {
            String storeIdStr = jwt.getClaim("store_id");
            if (storeIdStr == null || storeIdStr.isEmpty()) {
                return null;
            }
            return UUID.fromString(storeIdStr);
        } catch (Exception e) {
            log.error("Error extracting store_id from JWT: {}", e.getMessage());
            return null;
        }
    }

    // REMOVED: verifyStoreOwnershipAndGetStore() - replaced with JWT claim-based
    // authorization
    // This synchronous HTTP call to store-service was a major coupling point and
    // made
    // the system fragile. If store-service was down, order operations would fail
    // unnecessarily.
    //
    // New approach: Store ownership is verified via 'store_id' JWT claim.
    // See extractStoreId() method for implementation.

    // REMOVED: isStoreOwnerOfOrder() - replaced with JWT claim-based authorization
    // See extractStoreId() method for JWT-based store ownership verification

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
        Order updatedOrder = orderRepository.save(order);

        log.info("Courier assigned successfully: orderId={}, courierId={}", orderId, courierId);

        // Publish order.assigned event for notification service
        OrderAssignedContract contract = OrderAssignedContract
                .builder()
                .orderId(updatedOrder.getId())
                .storeId(updatedOrder.getStoreId())
                .courierId(courierId)
                .userId(updatedOrder.getUserId())
                .pickupAddress(updatedOrder.getPickupAddress())
                .shippingAddress(updatedOrder.getShippingAddress())
                .build();

        try {
            saveOutboxEvent(updatedOrder.getId().toString(), "order.assigned", contract);
            log.info("'order.assigned' event saved to Outbox. Order ID: {}, Courier ID: {}", orderId, courierId);
        } catch (Exception e) {
            log.error("ERROR occurred while saving event to Outbox. Order ID: {}. Error: {}", orderId,
                    e.getMessage());
            throw new RuntimeException("Failed to save outbox event", e);
        }
    }

    private void saveOutboxEvent(String aggregateId, String type, Object payloadObj) {
        try {
            String payload = objectMapper.writeValueAsString(payloadObj);
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType("ORDER")
                    .aggregateId(aggregateId)
                    .type(type)
                    .payload(payload)
                    .createdAt(LocalDateTime.now())
                    .processed(false)
                    .build();
            outboxRepository.save(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize/save outbox event", e);
        }
    }
}