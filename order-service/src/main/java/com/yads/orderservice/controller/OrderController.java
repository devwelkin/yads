// order-service/src/main/java/com/yads/orderservice/controller/OrderController.java
package com.yads.orderservice.controller;

import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.dto.OrderResponse;
import com.yads.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderRequest orderRequest,
            @AuthenticationPrincipal Jwt jwt) {
        OrderResponse response = orderService.createOrder(orderRequest, jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/my-orders")
    public ResponseEntity<List<OrderResponse>> getMyOrders(@AuthenticationPrincipal Jwt jwt) {
        List<OrderResponse> response = orderService.getMyOrders(jwt);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrderById(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt) {
        OrderResponse response = orderService.getOrderById(orderId, jwt);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/accept")
    public ResponseEntity<OrderResponse> acceptOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt) {
        OrderResponse response = orderService.acceptOrder(orderId, jwt);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/pickup")
    public ResponseEntity<OrderResponse> pickupOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt) {
        OrderResponse response = orderService.pickupOrder(orderId, jwt);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/deliver")
    public ResponseEntity<OrderResponse> deliverOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt) {
        OrderResponse response = orderService.deliverOrder(orderId, jwt);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt) {
        OrderResponse response = orderService.cancelOrder(orderId, jwt);
        return ResponseEntity.ok(response);
    }
}