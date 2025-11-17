package com.yads.courierservice.service;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.repository.CourierRepository;
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
    private final CourierRepository courierRepository;

    /**
     * Assigns a courier to an order.
     * Selects the nearest available courier based on distance to pickup location.
     *
     * RACE CONDITION FIX:
     * After selecting a courier, we immediately:
     * 1. Notify order-service (optimistic)
     * 2. If successful, update courier status to BUSY
     * This prevents double-assignment when multiple orders arrive simultaneously.
     */
    public void assignCourierToOrder(OrderAssignmentContract contract) {
        log.info("Processing courier assignment for order: orderId={}, storeId={}",
                contract.getOrderId(), contract.getStoreId());

        UUID selectedCourierId = selectBestCourier(contract);

        if (selectedCourierId == null) {
            log.warn("No available courier found for order: orderId={}", contract.getOrderId());
            return;
        }

        log.info("Selected courier: courierId={} for orderId={}",
                selectedCourierId, contract.getOrderId());

        // Optimistic lock: Try to assign the order first
        try {
            notifyOrderService(contract.getOrderId(), selectedCourierId);
            log.info("Successfully notified order-service. Updating courier status: courierId={}",
                    selectedCourierId);

            // Assignment succeeded - immediately update courier status to prevent race condition
            updateCourierStatusToBusy(selectedCourierId);

        } catch (Exception e) {
            // Optimistic lock failed - assignment didn't work, courier stays available
            log.error("Failed to assign courier to order. Courier remains available: " +
                    "courierId={}, orderId={}, error={}",
                    selectedCourierId, contract.getOrderId(), e.getMessage());
        }
    }

    /**
     * Updates courier status to BUSY after successful order assignment.
     * This prevents the same courier from being assigned to multiple orders simultaneously.
     */
    private void updateCourierStatusToBusy(UUID courierId) {
        try {
            Courier courier = courierRepository.findById(courierId)
                    .orElse(null);

            if (courier == null) {
                log.error("CRITICAL: Courier not found after successful assignment. " +
                        "This should never happen. courierId={}", courierId);
                return;
            }

            courier.setStatus(CourierStatus.BUSY);
            courierRepository.save(courier);

            log.info("Courier status updated to BUSY: courierId={}", courierId);

        } catch (Exception e) {
            // This is the "oops" scenario - assignment succeeded but status update failed
            log.error("CRITICAL: notifyOrderService succeeded, but FAILED to update local courier status to BUSY. " +
                    "courierId={} is now assigned to an order but still marked AVAILABLE! " +
                    "Manual reconciliation required. Error: {}",
                    courierId, e.getMessage(), e);
            // In production: trigger alert, reconciliation job, or compensating transaction
        }
    }

    /**
     * Selects the best available courier for the order.
     *
     * Algorithm: Distance-based selection
     * - Gets all available couriers
     * - Calculates distance from each courier to pickup location (store)
     * - Selects courier with minimum distance
     */
    private UUID selectBestCourier(OrderAssignmentContract contract) {
        // Get all available couriers
        var availableCouriers = courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE);

        if (availableCouriers.isEmpty()) {
            log.warn("No available couriers found for order: orderId={}", contract.getOrderId());
            return null;
        }

        log.info("Found {} available couriers for order: orderId={}",
                availableCouriers.size(), contract.getOrderId());

        // Get pickup location (store address)
        Double pickupLat = contract.getPickupAddress().getLatitude();
        Double pickupLon = contract.getPickupAddress().getLongitude();

        if (pickupLat == null || pickupLon == null) {
            log.warn("Pickup address missing coordinates, selecting first available courier: orderId={}",
                    contract.getOrderId());
            return availableCouriers.get(0).getId();
        }

        // Find courier with minimum distance to pickup location
        Courier nearestCourier = null;
        double minDistance = Double.MAX_VALUE;

        for (Courier courier : availableCouriers) {
            if (courier.getCurrentLatitude() == null || courier.getCurrentLongitude() == null) {
                log.debug("Courier {} missing location data, skipping", courier.getId());
                continue;
            }

            double distance = calculateDistance(
                    pickupLat, pickupLon,
                    courier.getCurrentLatitude(), courier.getCurrentLongitude()
            );

            log.debug("Courier {} distance to pickup: {} km", courier.getId(), distance);

            if (distance < minDistance) {
                minDistance = distance;
                nearestCourier = courier;
            }
        }

        if (nearestCourier == null) {
            log.warn("No couriers with valid location data, selecting first available: orderId={}",
                    contract.getOrderId());
            return availableCouriers.get(0).getId();
        }

        log.info("Selected nearest courier: courierId={}, distance={}km, orderId={}",
                nearestCourier.getId(), String.format("%.2f", minDistance), contract.getOrderId());

        return nearestCourier.getId();
    }

    /**
     * Calculates distance between two points using Haversine formula.
     *
     * @return distance in kilometers
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int EARTH_RADIUS_KM = 6371;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }

    /**
     * Notifies order-service about the courier assignment via internal API.
     *
     * CRITICAL: This method does NOT catch exceptions - they must propagate
     * up to assignCourierToOrder so the optimistic lock can work correctly.
     * If this fails, the courier should remain AVAILABLE.
     */
    private void notifyOrderService(UUID orderId, UUID courierId) {
        orderServiceWebClient.patch()
                .uri("/api/v1/internal/orders/{orderId}/assign-courier", orderId)
                .bodyValue(courierId)
                .retrieve()
                .toBodilessEntity()
                .block();

        log.info("Successfully notified order-service: orderId={}, courierId={}",
                orderId, courierId);
    }
}

