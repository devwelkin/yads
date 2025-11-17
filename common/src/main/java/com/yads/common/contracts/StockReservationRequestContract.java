package com.yads.common.contracts;

import com.yads.common.dto.BatchReserveItem;
import com.yads.common.model.Address;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Event contract sent by order-service to request stock reservation from store-service.
 * Part of the async saga pattern for order acceptance.
 */
@Data
@Builder
public class StockReservationRequestContract {
    private UUID orderId;
    private UUID storeId;
    private UUID userId; // For customer notification in case of failure
    private List<BatchReserveItem> items;

    // These addresses are needed for courier assignment on success
    private Address pickupAddress;
    private Address shippingAddress;
}

