package com.yads.common.contracts;

import com.yads.common.model.Address;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class OrderAssignmentContract {
    private UUID orderId;
    private UUID storeId;
    private Address pickupAddress;    // store'un adresi
    private Address shippingAddress;  // müşterinin adresi
}

