// order-service/src/main/java/com/yads/orderservice/dto/OrderItemRequest.java
package com.yads.orderservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.UUID;

@Data
public class OrderItemRequest {
    @NotNull(message = "Product ID cannot be null")
    private UUID productId;

    @NotNull(message = "Quantity cannot be null")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;
}