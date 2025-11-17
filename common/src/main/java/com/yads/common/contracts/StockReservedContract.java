package com.yads.common.contracts;

import com.yads.common.model.Address;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Event contract sent by store-service to confirm successful stock reservation.
 * Contains all data needed to trigger courier assignment saga.
 */
@Data
@Builder
public class StockReservedContract {
    // Carries the same data as OrderAssignmentContract for courier assignment
    private UUID orderId;
    private UUID storeId;
    private UUID userId;
    private Address pickupAddress;
    private Address shippingAddress;
}

