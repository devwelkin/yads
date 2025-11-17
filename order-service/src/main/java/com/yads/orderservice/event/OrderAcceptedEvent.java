package com.yads.orderservice.event;

import com.yads.common.model.Address;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Domain event published when an order is accepted by a store owner.
 * This event will be processed by a @TransactionalEventListener to send RabbitMQ message
 * AFTER the database transaction commits successfully.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAcceptedEvent {
    private UUID orderId;
    private UUID storeId;
    private UUID userId;
    private Address pickupAddress;
    private Address shippingAddress;
}

