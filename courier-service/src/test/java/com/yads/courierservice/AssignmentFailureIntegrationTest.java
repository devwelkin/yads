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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for courier assignment failure scenarios.
 *
 * Tests when courier assignment fails and courier.assignment.failed event
 * should be published:
 * 1. No available couriers at all
 * 2. All couriers are BUSY/OFFLINE/ON_BREAK
 * 3. No couriers with valid location data
 * 4. All couriers claimed by concurrent orders during assignment
 */
public class AssignmentFailureIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private CourierRepository courierRepository;

  @Autowired
  private OutboxRepository outboxRepository;

  @Autowired
  private IdempotentEventRepository idempotentEventRepository;

  @BeforeEach
  @AfterEach
  void cleanup() {
    courierRepository.deleteAll();
    outboxRepository.deleteAll();
    idempotentEventRepository.deleteAll();
  }

  @Test
  void should_publish_failure_event_when_no_couriers_exist() {
    // ARRANGE: No couriers in database
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID storeId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(storeId)
        .userId(userId)
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT: Send assignment request
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT: courier.assignment.failed event should be created
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(1, events.size());

      OutboxEvent event = events.get(0);
      assertEquals("courier.assignment.failed", event.getType());
      assertEquals("ORDER", event.getAggregateType());
      assertEquals(orderId.toString(), event.getAggregateId());
      assertFalse(event.isProcessed());

      // Verify payload contains order information
      assertTrue(event.getPayload().contains(orderId.toString()));
      assertTrue(event.getPayload().contains(userId.toString()));
      assertTrue(event.getPayload().contains("No available couriers") ||
          event.getPayload().contains("no available couriers") ||
          event.getPayload().contains("No couriers available"));
    });

    // ASSERT: Idempotency key should exist
    String eventKey = "ASSIGN_COURIER:" + orderId;
    assertTrue(idempotentEventRepository.existsById(eventKey));
  }

  @Test
  void should_publish_failure_event_when_all_couriers_are_busy() {
    // ARRANGE: All couriers are BUSY
    for (int i = 0; i < 3; i++) {
      Courier busyCourier = Courier.builder()
          .id(UUID.randomUUID())
          .status(CourierStatus.BUSY)
          .isActive(true)
          .currentLatitude(40.980 + (i * 0.01))
          .currentLongitude(29.025 + (i * 0.01))
          .build();
      courierRepository.save(busyCourier);
    }

    UUID orderId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT: Failure event should be published
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertFalse(events.isEmpty(), "Should have failure event");

      OutboxEvent failureEvent = events.stream()
          .filter(e -> e.getType().equals("courier.assignment.failed"))
          .findFirst()
          .orElseThrow(() -> new AssertionError("No failure event found"));

      assertEquals(orderId.toString(), failureEvent.getAggregateId());
    });

    // All couriers should remain BUSY (not changed)
    List<Courier> couriers = courierRepository.findAll();
    assertTrue(couriers.stream().allMatch(c -> c.getStatus() == CourierStatus.BUSY));
  }

  @Test
  void should_publish_failure_event_when_all_couriers_are_offline() {
    // ARRANGE: All couriers are OFFLINE
    for (int i = 0; i < 3; i++) {
      Courier offlineCourier = Courier.builder()
          .id(UUID.randomUUID())
          .status(CourierStatus.OFFLINE)
          .isActive(true)
          .currentLatitude(40.980 + (i * 0.01))
          .currentLongitude(29.025 + (i * 0.01))
          .build();
      courierRepository.save(offlineCourier);
    }

    UUID orderId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll().stream()
          .filter(e -> e.getType().equals("courier.assignment.failed"))
          .toList();

      assertEquals(1, events.size());
      assertEquals(orderId.toString(), events.get(0).getAggregateId());
    });
  }

  @Test
  void should_publish_failure_event_when_no_couriers_have_location() {
    // ARRANGE: Couriers with AVAILABLE status but no location data
    for (int i = 0; i < 3; i++) {
      Courier courierWithoutLocation = Courier.builder()
          .id(UUID.randomUUID())
          .status(CourierStatus.AVAILABLE)
          .isActive(true)
          .currentLatitude(null) // No location
          .currentLongitude(null)
          .build();
      courierRepository.save(courierWithoutLocation);
    }

    UUID orderId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT: Failure event should be published
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> failureEvents = outboxRepository.findAll().stream()
          .filter(e -> e.getType().equals("courier.assignment.failed"))
          .toList();

      assertEquals(1, failureEvents.size());
    });

    // Couriers should remain AVAILABLE (not assigned)
    List<Courier> couriers = courierRepository.findAll();
    assertTrue(couriers.stream().allMatch(c -> c.getStatus() == CourierStatus.AVAILABLE));
  }

  @Test
  void should_publish_failure_event_when_couriers_inactive() {
    // ARRANGE: Couriers are AVAILABLE but isActive=false
    for (int i = 0; i < 3; i++) {
      Courier inactiveCourier = Courier.builder()
          .id(UUID.randomUUID())
          .status(CourierStatus.AVAILABLE)
          .isActive(false) // Inactive
          .currentLatitude(40.980 + (i * 0.01))
          .currentLongitude(29.025 + (i * 0.01))
          .build();
      courierRepository.save(inactiveCourier);
    }

    UUID orderId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> failureEvents = outboxRepository.findAll().stream()
          .filter(e -> e.getType().equals("courier.assignment.failed"))
          .toList();

      assertFalse(failureEvents.isEmpty(), "Should have failure event for inactive couriers");
    });
  }

  @Test
  void should_contain_failure_reason_in_event_payload() {
    // ARRANGE: No couriers
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(UUID.randomUUID())
        .userId(userId)
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT: Event payload should contain failure reason
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(1, events.size());

      OutboxEvent event = events.get(0);
      String payload = event.getPayload();

      // Verify essential fields in payload
      assertTrue(payload.contains("orderId") || payload.contains("order_id"));
      assertTrue(payload.contains("userId") || payload.contains("user_id"));
      assertTrue(payload.contains("reason"));
      assertTrue(payload.contains(orderId.toString()));
      assertTrue(payload.contains(userId.toString()));
    });
  }

  @Test
  void should_still_assign_courier_when_pickup_has_no_coordinates() {
    // ARRANGE: AVAILABLE courier exists
    Courier courier = Courier.builder()
        .id(UUID.randomUUID())
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        .currentLatitude(40.980)
        .currentLongitude(29.025)
        .build();
    courierRepository.save(courier);

    UUID orderId = UUID.randomUUID();

    // Pickup address WITHOUT coordinates (will return unsorted list)
    Address pickupAddress = new Address();
    pickupAddress.setLatitude(null);
    pickupAddress.setLongitude(null);

    OrderAssignmentContract contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT
    rabbitTemplate.convertAndSend("q.courier_service.assign_order", contract);

    // ASSERT: Courier should still be assigned (distance sorting skipped, but
    // assignment continues)
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Courier updatedCourier = courierRepository.findById(courier.getId()).orElseThrow();
      assertEquals(CourierStatus.BUSY, updatedCourier.getStatus(),
          "Courier should be assigned even without pickup coordinates");
    });
  }
}
