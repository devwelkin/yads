package com.yads.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchReserveStockResponse {
    private UUID productId;
    private String productName;
    private Integer reservedQuantity;
    private Integer remainingStock;
    private boolean success;
    private String errorMessage;
}

