package com.yads.orderservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/orders")
@RequiredArgsConstructor
public class OrderInternalController {

    // This controller is reserved for future internal endpoints
    // Currently, all inter-service communication is handled via event-driven
    // architecture
}
