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

    // DEPRECATED: This synchronous REST endpoint has been replaced by event-driven pattern
    // Courier-service now publishes 'courier.assigned' events instead of making HTTP calls
    // See: CourierAssignedSubscriber for the new async implementation
    //
    // @PatchMapping("/{orderId}/assign-courier")
    // public ResponseEntity<Void> assignCourier(
    //         @PathVariable UUID orderId,
    //         @RequestBody UUID courierId) {
    //     orderService.assignCourierToOrder(orderId, courierId);
    //     return ResponseEntity.ok().build();
    // }
}

