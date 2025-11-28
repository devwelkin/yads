package com.yads.common.contracts;

import com.yads.common.model.Address;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAssignmentContract {
    private UUID orderId;
    private UUID storeId;
    private UUID userId; // CRITICAL: customer to notify
    private Address pickupAddress; // store'un adresi
    private Address shippingAddress; // müşterinin adresi
}
