package com.yads.common.contracts;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Event contract sent by courier-service when a courier is successfully assigned to an order.
 * This completes the async saga pattern - order-service updates its courierID based on this event.
 *
 * CRITICAL: This replaces the synchronous REST call that caused split-brain issues.
 */
@Data
@Builder
public class CourierAssignedContract {
    private UUID orderId;
    private UUID courierId;
    private UUID storeId;  // For potential notifications
    private UUID userId;   // For potential notifications
}

