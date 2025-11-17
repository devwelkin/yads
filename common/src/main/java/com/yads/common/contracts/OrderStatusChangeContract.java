package com.yads.common.contracts;

import com.yads.common.model.Address;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contract for order status change events.
 *
 * Used for events like:
 * - order.created
 * - order.on_the_way
 * - order.delivered
 *
 * Contains all information needed for notifications.
 */
@Data
@Builder
public class OrderStatusChangeContract {
    private UUID orderId;
    private UUID userId;           // customer
    private UUID storeId;
    private UUID courierId;        // nullable for order.created
    private String status;         // PENDING, PREPARING, ON_THE_WAY, DELIVERED, CANCELLED
    private BigDecimal totalPrice;
    private Address shippingAddress;
    private Address pickupAddress;
    private Instant createdAt;
}

