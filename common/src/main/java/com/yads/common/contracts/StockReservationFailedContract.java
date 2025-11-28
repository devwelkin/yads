package com.yads.common.contracts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Event contract sent by store-service when stock reservation fails.
 * Triggers order status rollback and customer notification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReservationFailedContract {
    private UUID orderId;
    private UUID userId; // For customer notification
    private String reason; // e.g., "Insufficient stock for product 'x'"
}
