// order-service/src/main/java/com/yads/orderservice/dto/OrderItemResponse.java
package com.yads.orderservice.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class OrderItemResponse {
    private UUID productId;
    private String productName;
    private Integer quantity;
    private BigDecimal price; // current selling price
}