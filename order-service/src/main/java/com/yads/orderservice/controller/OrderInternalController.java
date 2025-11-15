package com.yads.orderservice.controller;

import com.yads.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/orders")
@RequiredArgsConstructor
public class OrderInternalController {

    private final OrderService orderService;

    // This endpoint is used for internal communication between order-service and courier-service.
    @PatchMapping("/{orderId}/assign-courier")
    public ResponseEntity<Void> assignCourier(
            @PathVariable UUID orderId,
            @RequestBody UUID courierId) {
        orderService.assignCourierToOrder(orderId, courierId);
        return ResponseEntity.ok().build();
    }
}

