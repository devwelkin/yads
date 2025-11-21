package com.yads.courierservice;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.model.Address;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.model.OutboxEvent;
import com.yads.courierservice.repository.CourierRepository;
import com.yads.courierservice.repository.IdempotentEventRepository;
import com.yads.courierservice.repository.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for courier assignment flow.
 *
 * Tests the complete workflow:
 * 1. Receive OrderAssignmentContract from RabbitMQ
 * 2. Select nearest available courier using distance algorithm
 * 3. Atomically mark courier as BUSY with pessimistic lock
 * 4. Save courier.assigned event to outbox
 * 5. Handle race conditions with optimistic locking
 */
public class CourierAssignmentIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private CourierRepository courierRepository;

  @Autowired
  private OutboxRepository outboxRepository;

  @Autowired
  private IdempotentEventRepository idempotentEventRepository;

  @Autowired
  private BlockingQueue<Object> capturedMessages;

  @BeforeEach
  @AfterEach
  void cleanup() {
    courierRepository.deleteAll();
    outboxRepository.deleteAll();
    idempotentEventRepository.deleteAll();
    capturedMessages.clear();
  }

  // --- TEST CONFIGURATION (SPY FOR OUTBOUND EVENTS) ---
  @TestConfiguration
  static class TestRabbitConfig {

    @Bean
    public BlockingQueue<Object> capturedMessages() {
      return new LinkedBlockingQueue<>();
    }

    @Bean
    public Queue courierAssignedSpyQueue() {
      return new Queue("courier.assigned.spy.queue", false);
    }

    @Bean
    public Binding bindingCourierAssigned(Queue courierAssignedSpyQueue) {
      return BindingBuilder.bind(courierAssignedSpyQueue)
          .to(new DirectExchange("courier_events_exchange"))
          .with("courier.assigned");
    }

    @RabbitListener(queues = "courier.assigned.spy.queue")
    public void spyListener(Message message) {
      capturedMessages().offer(message);
    }
  }

  @Test
  void should_assign_nearest_available_courier_successfully() {
    // ARRANGE: Create 3 couriers at different distances from pickup location
    UUID storeId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    // Pickup location: Istanbul Kadikoy (40.990, 29.020)
    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    // Courier 1: Near (Kadikoy) - ~1 km distance
    Courier nearCourier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.980)
        .currentLongitude(29.025)
        .build();
    courierRepository.save(nearCourier);

    // Courier 2: Far (Bostanci) - ~8 km distance
    Courier farCourier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.950)
        .currentLongitude(29.090)
        .build();
    courierRepository.save(farCourier);

    // Courier 3: Medium distance - ~4 km
    Courier mediumCourier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.970)
        .currentLongitude(29.050)
        .build();
    courierRepository.save(mediumCourier);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(storeId)
        .userId(userId)
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT: Send order assignment event
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT: Nearest courier should be marked as BUSY
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Courier updated = courierRepository.findById(nearCourier.getId()).orElseThrow();
      assertEquals(CourierStatus.BUSY, updated.getStatus());
    });

    // Other couriers should remain AVAILABLE
    Courier farUpdated = courierRepository.findById(farCourier.getId()).orElseThrow();
    Courier mediumUpdated = courierRepository.findById(mediumCourier.getId()).orElseThrow();
    assertEquals(CourierStatus.AVAILABLE, farUpdated.getStatus());
    assertEquals(CourierStatus.AVAILABLE, mediumUpdated.getStatus());

    // ASSERT: Outbox event created
    List<OutboxEvent> events = outboxRepository.findAll();
    assertEquals(1, events.size());
    OutboxEvent event = events.get(0);
    assertEquals("courier.assigned", event.getType());
    assertEquals("COURIER", event.getAggregateType());
    assertEquals(nearCourier.getId().toString(), event.getAggregateId());
    assertFalse(event.isProcessed());
    assertTrue(event.getPayload().contains(orderId.toString()));
    assertTrue(event.getPayload().contains(nearCourier.getId().toString()));

    // ASSERT: Idempotency key created
    String eventKey = "ASSIGN_COURIER:" + orderId;
    assertTrue(idempotentEventRepository.existsById(eventKey));
  }

  @Test
  void should_skip_couriers_without_location_data() {
    // ARRANGE: Two couriers, one with location, one without
    UUID orderId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    // Courier 1: No location data
    Courier noLocationCourier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(null) // No location
        .currentLongitude(null)
        .build();
    courierRepository.save(noLocationCourier);

    // Courier 2: Has location data
    Courier validCourier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.980)
        .currentLongitude(29.025)
        .build();
    courierRepository.save(validCourier);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT: Valid courier should be assigned (courier without location skipped)
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Courier updated = courierRepository.findById(validCourier.getId()).orElseThrow();
      assertEquals(CourierStatus.BUSY, updated.getStatus());
    });

    // Courier without location should remain AVAILABLE
    Courier noLocationUpdated = courierRepository.findById(noLocationCourier.getId()).orElseThrow();
    assertEquals(CourierStatus.AVAILABLE, noLocationUpdated.getStatus());
  }

  @Test
  void should_skip_non_available_couriers() {
    // ARRANGE: Couriers in different statuses
    UUID orderId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    // Courier 1: OFFLINE
    Courier offlineCourier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.OFFLINE)
        .isActive(true)
        .currentLatitude(40.980)
        .currentLongitude(29.025)
        .build();
    courierRepository.save(offlineCourier);

    // Courier 2: BUSY
    Courier busyCourier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.BUSY)
        .isActive(true)
        .currentLatitude(40.985)
        .currentLongitude(29.030)
        .build();
    courierRepository.save(busyCourier);

    // Courier 3: AVAILABLE (should be selected)
    Courier availableCourier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.970)
        .currentLongitude(29.050)
        .build();
    courierRepository.save(availableCourier);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT: Only AVAILABLE courier should be assigned
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Courier updated = courierRepository.findById(availableCourier.getId()).orElseThrow();
      assertEquals(CourierStatus.BUSY, updated.getStatus());
    });

    // Other couriers should remain unchanged
    Courier offlineUpdated = courierRepository.findById(offlineCourier.getId()).orElseThrow();
    Courier busyUpdated = courierRepository.findById(busyCourier.getId()).orElseThrow();
    assertEquals(CourierStatus.OFFLINE, offlineUpdated.getStatus());
    assertEquals(CourierStatus.BUSY, busyUpdated.getStatus());
  }

  @Test
  void should_try_next_courier_if_first_becomes_unavailable() {
    // ARRANGE: Two available couriers
    UUID orderId1 = UUID.randomUUID();
    UUID orderId2 = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    // Courier 1: Nearest
    Courier courier1 = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.980)
        .currentLongitude(29.025)
        .build();
    courierRepository.save(courier1);

    // Courier 2: Further away
    Courier courier2 = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.950)
        .currentLongitude(29.090)
        .build();
    courierRepository.save(courier2);

    // ACT: Send two orders simultaneously
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

    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract1);
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract2);

    // ASSERT: Both couriers should be assigned (one per order)
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Courier updated1 = courierRepository.findById(courier1.getId()).orElseThrow();
      Courier updated2 = courierRepository.findById(courier2.getId()).orElseThrow();

      // Both should be BUSY
      assertEquals(CourierStatus.BUSY, updated1.getStatus());
      assertEquals(CourierStatus.BUSY, updated2.getStatus());
    });

    // Both orders should have outbox events
    List<OutboxEvent> events = outboxRepository.findAll();
    assertEquals(2, events.size());
  }
}
