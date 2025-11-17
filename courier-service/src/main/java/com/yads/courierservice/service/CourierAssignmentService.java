package com.yads.courierservice.service;

import com.yads.common.contracts.OrderAssignmentContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CourierAssignmentService {

    private final WebClient orderServiceWebClient;

    /**
     * Assigns a courier to an order.
     * TODO: Implement real courier selection logic based on:
     * - Courier availability
     * - Distance between pickup and shipping address
     * - Current courier workload
     * - Courier ratings
     */
    public void assignCourierToOrder(OrderAssignmentContract contract) {
        log.info("Processing courier assignment for order: orderId={}, storeId={}",
                contract.getOrderId(), contract.getStoreId());

        // TODO: Implement real courier selection algorithm
        // For now, using a mock courier ID as placeholder
        UUID selectedCourierId = selectBestCourier(contract);

        if (selectedCourierId == null) {
            log.warn("No available courier found for order: orderId={}", contract.getOrderId());
            return;
        }

        log.info("Selected courier: courierId={} for orderId={}",
                selectedCourierId, contract.getOrderId());

        // Call back to order-service to assign the courier
        notifyOrderService(contract.getOrderId(), selectedCourierId);
    }

    /**
     * Selects the best available courier for the order.
     * TODO: Implement real selection logic
     */
    private UUID selectBestCourier(OrderAssignmentContract contract) {
        // PLACEHOLDER: Return a mock courier ID
        // In production, this should:
        // 1. Query available couriers from database
        // 2. Calculate distances using pickup/shipping addresses
        // 3. Check courier availability and current workload
        // 4. Select the best match based on criteria

        UUID mockCourierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        log.info("MOCK: Selecting placeholder courier: {}", mockCourierId);
        return mockCourierId;
    }

    /**
     * Notifies order-service about the courier assignment via internal API.
     */
    private void notifyOrderService(UUID orderId, UUID courierId) {
        try {
            orderServiceWebClient.patch()
                    .uri("/api/v1/internal/orders/{orderId}/assign-courier", orderId)
                    .bodyValue(courierId)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Successfully notified order-service: orderId={}, courierId={}",
                    orderId, courierId);
        } catch (Exception e) {
            log.error("Failed to notify order-service: orderId={}, courierId={}, error={}",
                    orderId, courierId, e.getMessage(), e);
        }
    }
}

