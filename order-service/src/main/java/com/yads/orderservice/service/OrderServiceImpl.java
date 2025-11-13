// order-service/src/main/java/com/yads/orderservice/service/OrderServiceImpl.java
package com.yads.orderservice.service;

import com.yads.common.dto.ReserveStockRequest;
import com.yads.common.dto.StoreResponse;
import com.yads.orderservice.dto.OrderItemRequest;
import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.dto.OrderResponse;
import com.yads.orderservice.dto.ProductSnapshotDto;
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

                log.warn("Failed to reserve stock for product {}: {}", reqItem.getProductId(), e.getMessage());


                switch (e.getStatusCode()) {
                    case HttpStatus.NOT_FOUND ->
                            throw new IllegalArgumentException("Product not found: " + reqItem.getProductId());
                    case HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST -> {
                        String error = e.getResponseBodyAsString();
                        throw new IllegalArgumentException(error);
                    }
                    default ->
                            throw new RuntimeException("Error communicating with store service: " + e.getMessage());
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
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId)); // TODO: proper exception

        // 2. Check order status
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Order status must be PENDING to accept. Current status: " + order.getStatus());
        }

        // 3. Get userId and role from JWT
        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = jwt.getClaimAsStringList("roles");

        // 4. Role check - must be STORE_OWNER
        if (roles == null || !roles.contains("STORE_OWNER")) {
            throw new RuntimeException("Access Denied: Only store owners can accept orders"); // TODO: proper exception
        }

        // 5. Critical: Is this store owner the owner of this order's store?
        UUID storeId = order.getStoreId();

        try {
            // Get store information from store-service
            StoreResponse storeResponse = storeServiceWebClient.get()
                    .uri("/api/v1/stores/" + storeId)
                    .retrieve()
                    .bodyToMono(StoreResponse.class)
                    .block();

            // Compare store owner with user in JWT
            if (storeResponse == null || !storeResponse.getOwnerId().equals(userId)) {
                throw new RuntimeException("Access Denied: You are not the owner of this store"); // TODO: proper exception
            }

        } catch (WebClientResponseException e) {
            log.error("Error fetching store info for storeId: {}. Error: {}", storeId, e.getMessage());
            throw new RuntimeException("Error communicating with store service: " + e.getMessage());
        }

        // 6. All checks complete, update status
        order.setStatus(OrderStatus.PREPARING);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated to PREPARING. Order ID: {}", orderId);

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
    public OrderResponse getOrderById(UUID orderId, Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId)); // TODO: proper exception

        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("Access Denied"); // TODO: proper exception
        }

        return orderMapper.toOrderResponse(order);
    }
}