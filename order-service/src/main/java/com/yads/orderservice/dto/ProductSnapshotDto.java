// order-service/src/main/java/com/yads/orderservice/dto/ProductSnapshotDto.java
package com.yads.orderservice.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

// These are the expected fields when we call store-service /api/v1/products/{id}
@Data
public class ProductSnapshotDto {
    private UUID id;
    private String name;
    private BigDecimal price;
    private Integer stock;
    private boolean isAvailable;
    // We don't need to know which store/category this product belongs to,
    // we just need to get the price, stock, and name (snapshot) is enough.
}