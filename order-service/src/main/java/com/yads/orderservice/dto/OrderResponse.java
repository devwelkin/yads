// order-service/src/main/java/com/yads/orderservice/dto/OrderResponse.java
package com.yads.orderservice.dto;

import com.yads.orderservice.model.Address;
import com.yads.orderservice.model.OrderStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class OrderResponse {
    private UUID id;
    private UUID userId;
    private UUID storeId;
    private UUID courierId;
    private List<OrderItemResponse> items;
    private BigDecimal totalPrice;
    private OrderStatus status;
    private Address shippingAddress;
    private Instant createdAt;
}