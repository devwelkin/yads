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
import com.yads.orderservice.repository.OrderRepository;
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
    private final WebClient.Builder webClientBuilder;
    private final OrderMapper orderMapper;
    private final RabbitTemplate rabbitTemplate; // RabbitMQ için

    // store-service'in eureka'daki adresi
    private final String STORE_SERVICE_URL = "http://store-service";

    public OrderResponse createOrder(OrderRequest orderRequest, Jwt jwt) {
        log.info("Order creation process started. Store ID: {}", orderRequest.getStoreId());
        UUID userId = UUID.fromString(jwt.getSubject());

        // --- Step 1: Get All Products from store-service ---
        // Get the list of all products in that store using 'storeId'
        List<ProductSnapshotDto> storeProductsList = webClientBuilder.build()
                .get()
                .uri(STORE_SERVICE_URL + "/api/v1/stores/{storeId}/products", orderRequest.getStoreId())
                .retrieve()
                .bodyToFlux(ProductSnapshotDto.class) // Using Flux since we'll receive a list
                .collectList()
                .block(); // Converting async operation to sync and waiting

        if (storeProductsList == null || storeProductsList.isEmpty()) {
            log.warn("Store not found or store has no products. Store ID: {}", orderRequest.getStoreId());
            throw new IllegalArgumentException("Store not found or has no products.");
        }

        // Convert this list to a Map for faster lookup (Key: productId, Value: Product)
        Map<UUID, ProductSnapshotDto> storeProductMap = storeProductsList.stream()
                .collect(Collectors.toMap(ProductSnapshotDto::getId, product -> product));

        log.info("Retrieved {} products from store with ID: {}", storeProductMap.size(), orderRequest.getStoreId());

        // --- Step 2: Validate Cart and Create OrderItem List ---
        BigDecimal totalPrice = BigDecimal.ZERO;
        List<OrderItem> orderItems = new java.util.ArrayList<>();

        for (OrderItemRequest reqItem : orderRequest.getItems()) {
            ProductSnapshotDto productInfo = storeProductMap.get(reqItem.getProductId());

            // -- Start Validation --
            if (productInfo == null) {
                log.error("Product in cart not found in store. Product ID: {}", reqItem.getProductId());
                throw new IllegalArgumentException("Product not found: " + reqItem.getProductId());
            }
            if (!productInfo.isAvailable()) {
                log.error("Product in cart is not available (available=false). Product: {}", productInfo.getName());
                throw new IllegalArgumentException("Product not available: " + productInfo.getName());
            }
            if (productInfo.getStock() < reqItem.getQuantity()) {
                log.error("Insufficient stock. Product: {}, Requested: {}, Stock: {}", productInfo.getName(), reqItem.getQuantity(), productInfo.getStock());
                throw new IllegalArgumentException("Insufficient stock: " + productInfo.getName());
            }
            // -- End Validation --

            // Validation successful. Create OrderItem.
            OrderItem item = new OrderItem();
            // item.setOrder(order); // -> we'll do this later
            item.setProductId(productInfo.getId());
            item.setProductName(productInfo.getName()); // snapshot: copy name from store
            item.setPrice(productInfo.getPrice()); // snapshot: copy price from store
            item.setQuantity(reqItem.getQuantity());

            orderItems.add(item);

            // Calculate total price
            totalPrice = totalPrice.add(productInfo.getPrice().multiply(BigDecimal.valueOf(reqItem.getQuantity())));
        }
        log.info("Cart validated. Total Price: {}", totalPrice);

        // --- Step 3: Create Order and Save to DB ---
        Order order = new Order();
        order.setUserId(userId);
        order.setStoreId(orderRequest.getStoreId());
        order.setShippingAddress(orderRequest.getShippingAddress());
        order.setStatus(OrderStatus.PENDING);
        order.setTotalPrice(totalPrice);

        // Link OrderItems to Order (for bidirectional relationship)
        for (OrderItem item : orderItems) {
            item.setOrder(order);
        }
        order.setItems(orderItems);

        Order savedOrder = orderRepository.save(order);
        log.info("Order saved to database. ID: {}", savedOrder.getId());

        OrderResponse orderResponse = orderMapper.toOrderResponse(savedOrder);

        // --- Step 5: Send 'order.created' event to RabbitMQ ---
        // We send the response dto as the event.
        // This way the listening service (e.g. notification-service) doesn't need
        // to make another request to order-service for order details.

        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.created", orderResponse);
            log.info("'order.created' event sent to RabbitMQ. order id: {}", savedOrder.getId());
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. order id: {}. error: {}", savedOrder.getId(), e.getMessage());
            // note: we should not rollback the order transaction here (non-transactional).
            // compensation mechanism is a more complex scenario, for now we just log it.
        }

        // --- Step 5: Map and Return Response ---
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

        // güvenlik: kullanıcı sadece kendi siparişini görebilmeli
        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("Access Denied"); // TODO: proper exception
        }

        return orderMapper.toOrderResponse(order);
    }
}