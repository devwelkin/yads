// order-service/src/main/java/com/yads/orderservice/service/OrderService.java
package com.yads.orderservice.service;

import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.dto.OrderResponse;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

public interface OrderService {

    /**
     * Creates a new order.
     * This method synchronously calls the store-service to validate product prices and stock levels.
     * If successful, sends an event to RabbitMQ.
     */
    OrderResponse createOrder(OrderRequest orderRequest, Jwt jwt);

    /**
     * Lists orders for the currently logged-in user.
     */
    List<OrderResponse> getMyOrders(Jwt jwt);

    /**
     * Retrieves details of a specific order (with security checks).
     */
    OrderResponse getOrderById(UUID orderId, Jwt jwt);
}