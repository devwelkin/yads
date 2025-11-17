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
     * Accepts a pending order and changes status to PREPARING.
     * Transition: PENDING -> PREPARING
     */
    OrderResponse acceptOrder(UUID orderId, Jwt jwt);

    /**
     * Marks an order as picked up and changes status to ON_THE_WAY.
     * Transition: PREPARING -> ON_THE_WAY
     */
    OrderResponse pickupOrder(UUID orderId, Jwt jwt);

    /**
     * Marks an order as delivered and changes status to DELIVERED.
     * Transition: ON_THE_WAY -> DELIVERED
     */
    OrderResponse deliverOrder(UUID orderId, Jwt jwt);

    /**
     * Cancels an order from any status.
     * Transition: * -> CANCELLED
     */
    OrderResponse cancelOrder(UUID orderId, Jwt jwt);

    /**
     * Lists orders for the currently logged-in user.
     */
    List<OrderResponse> getMyOrders(Jwt jwt);

    /**
     * Retrieves details of a specific order (with security checks).
     */
    OrderResponse getOrderById(UUID orderId, Jwt jwt);

    /**
     * Assigns a courier to an order (internal use only).
     * Called by courier-service after courier assignment.
     */
    void assignCourierToOrder(UUID orderId, UUID courierId);
}