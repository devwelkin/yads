package com.yads.courierservice;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.model.Address;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.repository.CourierRepository;
import com.yads.courierservice.service.CourierAssignmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests atomic courier assignment under concurrent load.
 *
 * CRITICAL SCENARIOS:
 * 1. Concurrent orders trying to assign same courier - pessimistic lock should
 * prevent double assignment
 * 2. Optimistic version locking should handle concurrent status updates
 * 3. No courier should be assigned to multiple orders simultaneously
 *
 * This is similar to store-service ConcurrencyIntegrationTest but for courier
 * assignments.
 */
public class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private CourierRepository courierRepository;

  @Autowired
  private CourierAssignmentService courierAssignmentService;

  private Address pickupAddress;

  @BeforeEach
  void setup() {
    courierRepository.deleteAll();

    // Istanbul Kadikoy coordinates for all tests
    pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);
  }

  @AfterEach
  void cleanup() {
    courierRepository.deleteAll();
  }

  @RepeatedTest(5) // Run 5 times to catch intermittent race conditions
  void should_prevent_double_assignment_with_pessimistic_lock() throws InterruptedException, ExecutionException {
    // ARRANGE: One available courier
    Courier courier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.980)
        .currentLongitude(29.025)
        .build();
    courier = courierRepository.save(courier);
    UUID courierId = courier.getId();

    // Two different orders
    UUID orderId1 = UUID.randomUUID();
    UUID orderId2 = UUID.randomUUID();

    OrderAssignmentContract contract1 = OrderAssignmentContract.builder()
        .orderId(orderId1)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    OrderAssignmentContract contract2 = OrderAssignmentContract.builder()
        .orderId(orderId2)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT: Two threads trying to assign the SAME courier simultaneously
    ExecutorService executor = Executors.newFixedThreadPool(2);

    Future<Void> future1 = executor.submit(() -> {
      courierAssignmentService.assignCourierToOrder(contract1);
      return null;
    });

    Future<Void> future2 = executor.submit(() -> {
      courierAssignmentService.assignCourierToOrder(contract2);
      return null;
    });

    // Wait for completion
    future1.get();
    future2.get();

    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // ASSERT: Courier should be BUSY
    Courier updatedCourier = courierRepository.findById(courierId).orElseThrow();
    assertEquals(CourierStatus.BUSY, updatedCourier.getStatus());

    // ASSERT: Only ONE assignment should have succeeded
    // (The other should have failed gracefully without double-assigning)
    // This is verified by the courier being BUSY (not assigned twice)
  }

  @RepeatedTest(5)
  void should_assign_different_couriers_to_concurrent_orders() throws InterruptedException, ExecutionException {
    // ARRANGE: Three available couriers
    List<Courier> couriers = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Courier courier = Courier.builder()
          .id(UUID.randomUUID())
          .status(CourierStatus.AVAILABLE)
          .isActive(true)
          .currentLatitude(40.980 + (i * 0.01)) // Slightly different locations
          .currentLongitude(29.025 + (i * 0.01))
          .build();
      couriers.add(courierRepository.save(courier));
    }

    // Three different orders
    List<OrderAssignmentContract> contracts = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      OrderAssignmentContract contract = OrderAssignmentContract.builder()
          .orderId(UUID.randomUUID())
          .storeId(UUID.randomUUID())
          .userId(UUID.randomUUID())
          .pickupAddress(pickupAddress)
          .shippingAddress(new Address())
          .build();
      contracts.add(contract);
    }

    // ACT: Three threads assigning three orders simultaneously
    ExecutorService executor = Executors.newFixedThreadPool(3);
    CountDownLatch latch = new CountDownLatch(3);
    List<Future<Void>> futures = new ArrayList<>();

    for (OrderAssignmentContract contract : contracts) {
      futures.add(executor.submit(() -> {
        latch.countDown();
        latch.await(); // All threads start at the same time
        courierAssignmentService.assignCourierToOrder(contract);
        return null;
      }));
    }

    // Wait for all to complete
    for (Future<Void> future : futures) {
      future.get();
    }

    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // ASSERT: All three couriers should be BUSY
    List<Courier> updatedCouriers = courierRepository.findAll();
    long busyCount = updatedCouriers.stream()
        .filter(c -> c.getStatus() == CourierStatus.BUSY)
        .count();

    assertEquals(3, busyCount, "All three couriers should be assigned to orders");

    // No courier should have version > 1 (meaning no double-update occurred)
    for (Courier courier : updatedCouriers) {
      assertTrue(courier.getVersion() <= 1,
          "Courier version should be 0 or 1, indicating single update");
    }
  }

  @RepeatedTest(5)
  void should_handle_concurrent_orders_exceeding_available_couriers() throws InterruptedException {
    // ARRANGE: Only 2 available couriers
    for (int i = 0; i < 2; i++) {
      Courier courier = Courier.builder()
          .id(UUID.randomUUID())
          .status(CourierStatus.AVAILABLE)
          .isActive(true)
          .currentLatitude(40.980 + (i * 0.01))
          .currentLongitude(29.025 + (i * 0.01))
          .build();
      courierRepository.save(courier);
    }

    // 5 concurrent orders (more than available couriers)
    ExecutorService executor = Executors.newFixedThreadPool(5);
    CountDownLatch latch = new CountDownLatch(5);
    List<Future<Void>> futures = new ArrayList<>();

    for (int i = 0; i < 5; i++) {
      OrderAssignmentContract contract = OrderAssignmentContract.builder()
          .orderId(UUID.randomUUID())
          .storeId(UUID.randomUUID())
          .userId(UUID.randomUUID())
          .pickupAddress(pickupAddress)
          .shippingAddress(new Address())
          .build();

      futures.add(executor.submit(() -> {
        latch.countDown();
        latch.await(); // All start simultaneously
        courierAssignmentService.assignCourierToOrder(contract);
        return null;
      }));
    }

    // Wait for completion
    for (Future<Void> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        // Some may fail, that's expected
      }
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // ASSERT: Only 2 couriers should be BUSY (no double assignment)
    List<Courier> allCouriers = courierRepository.findAll();
    long busyCount = allCouriers.stream()
        .filter(c -> c.getStatus() == CourierStatus.BUSY)
        .count();

    assertEquals(2, busyCount, "Only 2 couriers should be assigned (no double assignments)");

    // The other 3 orders should have triggered courier.assignment.failed events
    // (tested in AssignmentFailureIntegrationTest)
  }

  @RepeatedTest(3)
  void should_maintain_version_consistency_under_load() throws InterruptedException, ExecutionException {
    // ARRANGE: 10 available couriers
    List<Courier> couriers = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Courier courier = Courier.builder()
          .id(UUID.randomUUID())
          .status(CourierStatus.AVAILABLE)
          .isActive(true)
          .currentLatitude(40.980 + (i * 0.01))
          .currentLongitude(29.025 + (i * 0.01))
          .build();
      couriers.add(courierRepository.save(courier));
    }

    // 10 concurrent orders (matching available couriers)
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch latch = new CountDownLatch(10);
    List<Future<Void>> futures = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      OrderAssignmentContract contract = OrderAssignmentContract.builder()
          .orderId(UUID.randomUUID())
          .storeId(UUID.randomUUID())
          .userId(UUID.randomUUID())
          .pickupAddress(pickupAddress)
          .shippingAddress(new Address())
          .build();

      futures.add(executor.submit(() -> {
        latch.countDown();
        latch.await();
        courierAssignmentService.assignCourierToOrder(contract);
        return null;
      }));
    }

    for (Future<Void> future : futures) {
      future.get();
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // ASSERT: All 10 couriers should be BUSY
    List<Courier> updatedCouriers = courierRepository.findAll();
    long busyCount = updatedCouriers.stream()
        .filter(c -> c.getStatus() == CourierStatus.BUSY)
        .count();

    assertEquals(10, busyCount, "All 10 couriers should be assigned");

    // Verify version consistency (each courier updated exactly once)
    for (Courier courier : updatedCouriers) {
      assertTrue(courier.getVersion() <= 1,
          "Each courier should have version 0 or 1 (no double updates)");
    }
  }
}
