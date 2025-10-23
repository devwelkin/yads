// order-service/src/main/java/com/yads/orderservice/service/OrderServiceImpl.java
package com.yads.orderservice.service;

import com.yads.orderservice.dto.OrderItemRequest;
import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.dto.OrderResponse;
import com.yads.orderservice.dto.ProductSnapshotDto;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono; // WebClient (webflux) için

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
    private final ProductSnapshotRepository snapshotRepository;


    @Override
    @Transactional // Add @transactional
    public OrderResponse createOrder(OrderRequest orderRequest, Jwt jwt) {
        log.info("Order creation process started. Store ID: {}", orderRequest.getStoreId());
        UUID userId = UUID.fromString(jwt.getSubject());


        // --- Step 2: Validate cart
        BigDecimal totalPrice = BigDecimal.ZERO;
        List<OrderItem> orderItems = new java.util.ArrayList<>();

        for (OrderItemRequest reqItem : orderRequest.getItems()) {
            // Get from local db
            ProductSnapshot productInfo = snapshotRepository.findById(reqItem.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + reqItem.getProductId()));

            // --- Validation ---
            // 1. Does product belong to this store?
            if (!productInfo.getStoreId().equals(orderRequest.getStoreId())) {
                throw new IllegalArgumentException("Product " + productInfo.getName() + " does not belong to store " + orderRequest.getStoreId());
            }

            // 2. Is product available?
            if (!productInfo.isAvailable()) {
                log.error("Product in cart is not available. Product: {}", productInfo.getName());
                throw new IllegalArgumentException("Product not available: " + productInfo.getName());
            }

            // 3. Is there enough stock?
            if (productInfo.getStock() < reqItem.getQuantity()) {
                log.error("Insufficient stock. Product: {}, Requested: {}, Stock: {}", productInfo.getName(), reqItem.getQuantity(), productInfo.getStock());
                throw new IllegalArgumentException("Insufficient stock: " + productInfo.getName());
            }
            // -- End validation --

            // Create orderitem
            OrderItem item = new OrderItem();
            item.setProductId(productInfo.getProductId()); // id name changed
            item.setProductName(productInfo.getName());
            item.setPrice(productInfo.getPrice());
            item.setQuantity(reqItem.getQuantity());

            orderItems.add(item);

            // Calculate total price
            totalPrice = totalPrice.add(productInfo.getPrice().multiply(BigDecimal.valueOf(reqItem.getQuantity())));

            // (optional but important) Update stock
            // We could make this async but let's keep it here for now
            productInfo.setStock(productInfo.getStock() - reqItem.getQuantity());
            snapshotRepository.save(productInfo);
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