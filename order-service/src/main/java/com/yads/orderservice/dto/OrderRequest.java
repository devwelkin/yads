// order-service/src/main/java/com/yads/orderservice/dto/OrderRequest.java
package com.yads.orderservice.dto;

import com.yads.orderservice.model.Address;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class OrderRequest {
    @NotNull(message = "Store ID cannot be null")
    private UUID storeId;

    @NotNull(message = "Shipping address cannot be null")
    @Valid // Triggers validation for fields inside address (if we set rules for address)
    private Address shippingAddress;

    @NotEmpty(message = "Order must contain at least one item")
    @Valid // Triggers validation for each OrderItemRequest in the list
    private List<OrderItemRequest> items;
}