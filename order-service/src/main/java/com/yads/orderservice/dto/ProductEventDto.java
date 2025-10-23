package com.yads.orderservice.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class ProductEventDto {
    private UUID productId;
    private String name;
    private BigDecimal price;
    private Integer stock;
    private boolean isAvailable;
    private UUID storeId;
}