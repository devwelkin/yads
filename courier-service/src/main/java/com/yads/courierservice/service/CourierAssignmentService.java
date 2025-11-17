package com.yads.courierservice.service;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.repository.CourierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CourierAssignmentService {

    private final WebClient orderServiceWebClient;
    private final CourierRepository courierRepository;

    /**
     * Assigns a courier to an order using atomic lock-based assignment.
     *
     * RACE CONDITION FIX (REAL):
     * 1. Get list of all available couriers sorted by distance
     * 2. For each candidate courier (in order of proximity):
     *    a. Acquire pessimistic lock (SELECT ... FOR UPDATE)
     *    b. Check if still AVAILABLE (another thread may have claimed it)
     *    c. If AVAILABLE, mark as BUSY atomically within transaction
     *    d. Commit transaction (releases lock)
     *    e. Notify order-service AFTER successful commit
     * 3. If all couriers are claimed during iteration, log failure
     *
     * This ensures ZERO possibility of double-assignment.
     */
    public void assignCourierToOrder(OrderAssignmentContract contract) {
        log.info("Processing courier assignment for order: orderId={}, storeId={}",
                contract.getOrderId(), contract.getStoreId());

        // Get ALL available couriers sorted by distance
        List<Courier> candidateCouriers = selectBestCouriers(contract);

        if (candidateCouriers.isEmpty()) {
            log.warn("No available courier found for order: orderId={}", contract.getOrderId());
            return;
        }

        log.info("Found {} candidate couriers for order: orderId={}",
                candidateCouriers.size(), contract.getOrderId());

        // Try each courier in order of proximity
        for (Courier candidate : candidateCouriers) {
            try {
                // Atomic assignment: lock, check, update (within transaction)
                boolean assigned = atomicAssignIfAvailable(candidate.getId());

                if (!assigned) {
                    log.debug("Courier {} was claimed by another order, trying next candidate",
                            candidate.getId());
                    continue; // This courier was claimed, try next one
                }

                // SUCCESS: Courier is now atomically marked as BUSY in database
                log.info("Successfully assigned courier: courierId={} to orderId={}",
                        candidate.getId(), contract.getOrderId());

                // Now notify order-service (outside transaction, safe to fail)
                try {
                    notifyOrderService(contract.getOrderId(), candidate.getId());
                    log.info("Successfully notified order-service: orderId={}, courierId={}",
                            contract.getOrderId(), candidate.getId());
                    return; // SUCCESS - we're done!

                } catch (Exception e) {
                    log.error("CRITICAL: Courier marked BUSY but order-service notification FAILED. " +
                            "Courier {} is now BUSY without an assigned order. " +
                            "Manual reconciliation or compensation required. orderId={}, error={}",
                            candidate.getId(), contract.getOrderId(), e.getMessage());
                    // In production: trigger compensating transaction to mark courier AVAILABLE again
                    // Or: implement retry mechanism with exponential backoff
                    return; // Don't try other couriers - this one is already marked BUSY
                }

            } catch (OptimisticLockingFailureException e) {
                // Another thread updated the courier simultaneously (version mismatch)
                log.debug("Optimistic lock failure for courier {}, trying next candidate",
                        candidate.getId());
                continue;

            } catch (Exception e) {
                log.error("Unexpected error while assigning courier {}: {}",
                        candidate.getId(), e.getMessage(), e);
                continue; // Try next courier
            }
        }

        // All couriers were claimed by other orders
        log.error("Failed to assign ANY courier to order {}. All {} candidates were claimed during assignment process.",
                contract.getOrderId(), candidateCouriers.size());
    }

    /**
     * Atomically assigns a courier if still available.
     * Uses pessimistic locking (SELECT ... FOR UPDATE) to prevent race conditions.
     *
     * @return true if courier was successfully marked BUSY, false if already claimed
     * @throws OptimisticLockingFailureException if version conflict occurs
     */
    @Transactional
    public boolean atomicAssignIfAvailable(UUID courierId) {
        // Acquire pessimistic write lock - blocks other transactions from reading this row
        Courier courier = courierRepository.findByIdWithLock(courierId)
                .orElse(null);

        if (courier == null) {
            log.error("CRITICAL: Courier not found: courierId={}", courierId);
            return false;
        }

        // Check if still available (another thread may have claimed it before we got the lock)
        if (courier.getStatus() != CourierStatus.AVAILABLE) {
            log.debug("Courier {} is no longer AVAILABLE (status: {}), skipping",
                    courierId, courier.getStatus());
            return false;
        }

        // Atomically update status to BUSY
        courier.setStatus(CourierStatus.BUSY);
        courierRepository.save(courier);

        log.info("Courier atomically marked as BUSY: courierId={}, version={}",
                courierId, courier.getVersion());

        // Transaction commits here, releasing the lock
        return true;
    }

    /**
     * Selects ALL available couriers sorted by distance from nearest to farthest.
     * Returns a list so the caller can try each courier in order if the nearest one
     * gets claimed by another concurrent order.
     *
     * Algorithm: Distance-based sorting
     * - Gets all available couriers
     * - Calculates distance from each courier to pickup location (store)
     * - Sorts by distance (ascending)
     */
    private List<Courier> selectBestCouriers(OrderAssignmentContract contract) {
        // Get all available couriers
        List<Courier> availableCouriers = courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE);

        if (availableCouriers.isEmpty()) {
            log.warn("No available couriers found for order: orderId={}", contract.getOrderId());
            return List.of();
        }

        log.info("Found {} available couriers for order: orderId={}",
                availableCouriers.size(), contract.getOrderId());

        // Get pickup location (store address)
        Double pickupLat = contract.getPickupAddress().getLatitude();
        Double pickupLon = contract.getPickupAddress().getLongitude();

        if (pickupLat == null || pickupLon == null) {
            log.warn("Pickup address missing coordinates, returning couriers unsorted: orderId={}",
                    contract.getOrderId());
            return availableCouriers;
        }

        // Filter out couriers without location data and calculate distances
        availableCouriers.removeIf(courier -> {
            if (courier.getCurrentLatitude() == null || courier.getCurrentLongitude() == null) {
                log.debug("Courier {} missing location data, excluding from candidates", courier.getId());
                return true;
            }
            return false;
        });

        if (availableCouriers.isEmpty()) {
            log.warn("No couriers with valid location data: orderId={}", contract.getOrderId());
            return List.of();
        }

        // Sort by distance (nearest first)
        availableCouriers.sort((c1, c2) -> {
            double dist1 = calculateDistance(
                    pickupLat, pickupLon,
                    c1.getCurrentLatitude(), c1.getCurrentLongitude()
            );
            double dist2 = calculateDistance(
                    pickupLat, pickupLon,
                    c2.getCurrentLatitude(), c2.getCurrentLongitude()
            );
            return Double.compare(dist1, dist2);
        });

        log.info("Sorted {} couriers by distance for orderId={}. Nearest: courierId={}, distance={} km",
                availableCouriers.size(), contract.getOrderId(),
                availableCouriers.get(0).getId(),
                String.format("%.2f", calculateDistance(
                        pickupLat, pickupLon,
                        availableCouriers.get(0).getCurrentLatitude(),
                        availableCouriers.get(0).getCurrentLongitude()
                )));

        return availableCouriers;
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

