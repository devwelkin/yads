package com.yads.common.contracts;

import com.yads.common.model.Address;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Contract for order courier assignment events.
 *
 * Published by order-service after courier-service assigns a courier to an order.
 * Used by notification-service to notify the courier about the new assignment.
 */
@Data
@Builder
public class OrderAssignedContract {
    private UUID orderId;
    private UUID storeId;
    private UUID courierId;
    private UUID userId;           // customer
    private Address pickupAddress;
    private Address shippingAddress;
}

