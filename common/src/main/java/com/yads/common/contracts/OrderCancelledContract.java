package com.yads.common.contracts;

import com.yads.common.dto.BatchReserveItem;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Contract for order cancellation events.
 *
 * CRITICAL: This contract includes the 'oldStatus' field to prevent GHOST
 * INVENTORY.
 *
 * Stock should ONLY be restored if the order was previously in PREPARING or
 * ON_THE_WAY status.
 * If the order was PENDING, stock was never deducted, so restoration would
 * create phantom stock.
 *
 * Example:
 * - User cancels a PENDING order: oldStatus=PENDING -> NO stock restoration
 * - Store cancels a PREPARING order: oldStatus=PREPARING -> RESTORE stock
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelledContract {
    private UUID orderId;
    private UUID storeId;
    private UUID userId; // CRITICAL: customer to notify
    private UUID courierId; // nullable: courier to notify (if assigned)
    private String oldStatus; // CRITICAL: Used to determine if stock restoration is needed
    private List<BatchReserveItem> items;
}
