package com.yads.common.contracts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Event contract sent by courier-service when courier assignment fails.
 * Triggers order cancellation and customer notification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourierAssignmentFailedContract {
    private UUID orderId;
    private UUID userId; // For customer notification
    private UUID storeId; // For store notification
    private String reason; // e.g., "No available couriers in the area"
}
