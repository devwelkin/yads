package com.yads.orderservice;

import com.yads.common.contracts.CourierAssignedContract;
import com.yads.common.contracts.StockReservedContract;
import com.yads.common.model.Address;
import com.yads.orderservice.config.AmqpConfig;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for idempotency guarantees.
 * Tests that duplicate events are handled gracefully without side effects.
 */
public class IdempotencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private OrderRepository orderRepository;

  @BeforeEach
  @AfterEach
  void cleanup() {
    orderRepository.deleteAll();
  }

  // --- COURIER ASSIGNMENT IDEMPOTENCY ---

  @Test
  void should_handle_duplicate_courier_assigned_events_idempotently() {
    // ARRANGE: Create order in PREPARING state
    UUID courierId = UUID.randomUUID();
    Order order = new Order();
    order.setStoreId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setStatus(OrderStatus.PREPARING);
    order.setTotalPrice(BigDecimal.TEN);
    order.setCourierId(null); // Not yet assigned

    Order savedOrder = orderRepository.save(order);
    UUID orderId = savedOrder.getId();

    CourierAssignedContract contract = CourierAssignedContract.builder()
        .orderId(orderId)
        .courierId(courierId)
        .build();

    // ACT: Send the SAME event TWICE
    rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNED, contract);

    // Wait for first processing
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      Order updated = orderRepository.findById(orderId).orElseThrow();
      assertEquals(courierId, updated.getCourierId());
    });

    // Send duplicate event
    rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNED, contract);

    // Wait a bit to ensure second event is also processed
    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // ASSERT: Courier ID should still be the same (no double assignment)
    Order finalOrder = orderRepository.findById(orderId).orElseThrow();
    assertEquals(courierId, finalOrder.getCourierId());
    assertEquals(OrderStatus.PREPARING, finalOrder.getStatus());

    // ASSERT: No exception was thrown (graceful handling)
    // This test passes if no exception propagated
  }

  @Test
  void should_ignore_courier_assignment_when_already_assigned() {
    // ARRANGE: Order ALREADY has a courier assigned
    UUID existingCourierId = UUID.randomUUID();
    UUID newCourierId = UUID.randomUUID();

    Order order = new Order();
    order.setStoreId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setStatus(OrderStatus.PREPARING);
    order.setTotalPrice(BigDecimal.TEN);
    order.setCourierId(existingCourierId); // Already assigned

    Order savedOrder = orderRepository.save(order);
    UUID orderId = savedOrder.getId();

    // ACT: Try to assign a DIFFERENT courier
    CourierAssignedContract contract = CourierAssignedContract.builder()
        .orderId(orderId)
        .courierId(newCourierId)
        .build();

    rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNED, contract);

    // Wait for processing
    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // ASSERT: Original courier should remain (idempotency protection)
    Order finalOrder = orderRepository.findById(orderId).orElseThrow();
    assertEquals(existingCourierId, finalOrder.getCourierId(),
        "Original courier assignment must be preserved");
    assertNotEquals(newCourierId, finalOrder.getCourierId());
  }

  // --- STOCK RESERVATION IDEMPOTENCY ---

  @Test
  void should_handle_duplicate_stock_reserved_events_idempotently() {
    // ARRANGE: Create order in RESERVING_STOCK state
    Order order = new Order();
    order.setStoreId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setStatus(OrderStatus.RESERVING_STOCK);
    order.setTotalPrice(BigDecimal.TEN);

    Order savedOrder = orderRepository.save(order);
    UUID orderId = savedOrder.getId();

    Address pickupAddress = new Address();
    pickupAddress.setCity("Istanbul");
    pickupAddress.setStreet("Test Street");

    StockReservedContract contract = StockReservedContract.builder()
        .orderId(orderId)
        .storeId(savedOrder.getStoreId())
        .userId(savedOrder.getUserId())
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();

    // ACT: Send the SAME event TWICE
    rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVED, contract);

    // Wait for first processing
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      Order updated = orderRepository.findById(orderId).orElseThrow();
      assertEquals(OrderStatus.PREPARING, updated.getStatus());
      assertNotNull(updated.getPickupAddress());
    });

    // Send duplicate event
    rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVED, contract);

    // Wait for second processing attempt
    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // ASSERT: Status should still be PREPARING (not double-processed)
    Order finalOrder = orderRepository.findById(orderId).orElseThrow();
    assertEquals(OrderStatus.PREPARING, finalOrder.getStatus());
    assertNotNull(finalOrder.getPickupAddress());
    assertEquals("Istanbul", finalOrder.getPickupAddress().getCity());

    // No exception should be thrown
  }

  @Test
  void should_ignore_stock_reserved_if_order_already_cancelled() {
    // ARRANGE: Order is already CANCELLED (e.g., customer cancelled while stock was
    // being reserved)
    Order order = new Order();
    order.setStoreId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setStatus(OrderStatus.CANCELLED); // Already cancelled
    order.setTotalPrice(BigDecimal.TEN);

    Order savedOrder = orderRepository.save(order);
    UUID orderId = savedOrder.getId();

    StockReservedContract contract = StockReservedContract.builder()
        .orderId(orderId)
        .storeId(savedOrder.getStoreId())
        .userId(savedOrder.getUserId())
        .pickupAddress(new Address())
        .shippingAddress(new Address())
        .build();

    // ACT: Send stock reserved event for cancelled order
    rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVED, contract);

    // Wait for processing
    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // ASSERT: Order should remain CANCELLED (event ignored)
    Order finalOrder = orderRepository.findById(orderId).orElseThrow();
    assertEquals(OrderStatus.CANCELLED, finalOrder.getStatus());
    assertNull(finalOrder.getPickupAddress(), "Pickup address should not be set for cancelled order");
  }

  // --- EDGE CASE: Out-of-Order Event Processing ---

  @Test
  void should_ignore_courier_assigned_for_cancelled_order() {
    // ARRANGE: Order gets cancelled BEFORE courier assignment completes
    Order order = new Order();
    order.setStoreId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setStatus(OrderStatus.CANCELLED);
    order.setTotalPrice(BigDecimal.TEN);

    Order savedOrder = orderRepository.save(order);
    UUID orderId = savedOrder.getId();

    // ACT: Late-arriving courier assignment event
    CourierAssignedContract contract = CourierAssignedContract.builder()
        .orderId(orderId)
        .courierId(UUID.randomUUID())
        .build();

    rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNED, contract);

    // Wait for processing
    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // ASSERT: Courier should NOT be assigned (order is cancelled)
    Order finalOrder = orderRepository.findById(orderId).orElseThrow();
    assertEquals(OrderStatus.CANCELLED, finalOrder.getStatus());
    assertNull(finalOrder.getCourierId(), "Courier should not be assigned to cancelled order");
  }

  @Test
  void should_ignore_courier_assigned_for_delivered_order() {
    // ARRANGE: Order is already DELIVERED (edge case: very late event)
    UUID existingCourierId = UUID.randomUUID();

    Order order = new Order();
    order.setStoreId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setStatus(OrderStatus.DELIVERED);
    order.setTotalPrice(BigDecimal.TEN);
    order.setCourierId(existingCourierId);

    Order savedOrder = orderRepository.save(order);
    UUID orderId = savedOrder.getId();

    // ACT: Late-arriving courier assignment event (duplicate or delayed)
    CourierAssignedContract contract = CourierAssignedContract.builder()
        .orderId(orderId)
        .courierId(existingCourierId)
        .build();

    rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNED, contract);

    // Wait for processing
    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // ASSERT: Order status should remain DELIVERED
    Order finalOrder = orderRepository.findById(orderId).orElseThrow();
    assertEquals(OrderStatus.DELIVERED, finalOrder.getStatus());
    assertEquals(existingCourierId, finalOrder.getCourierId());
  }

  // --- STRESS TEST: Multiple Duplicate Events ---

  @Test
  void should_handle_rapid_fire_duplicate_events() {
    // ARRANGE
    UUID courierId = UUID.randomUUID();
    Order order = new Order();
    order.setStoreId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setStatus(OrderStatus.PREPARING);
    order.setTotalPrice(BigDecimal.TEN);

    Order savedOrder = orderRepository.save(order);
    UUID orderId = savedOrder.getId();

    CourierAssignedContract contract = CourierAssignedContract.builder()
        .orderId(orderId)
        .courierId(courierId)
        .build();

    // ACT: Send the same event 5 times rapidly
    for (int i = 0; i < 5; i++) {
      rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNED, contract);
    }

    // Wait for all events to be processed
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Order updated = orderRepository.findById(orderId).orElseThrow();
      assertNotNull(updated.getCourierId());
    });

    // ASSERT: Should handle all duplicates gracefully
    Order finalOrder = orderRepository.findById(orderId).orElseThrow();
    assertEquals(courierId, finalOrder.getCourierId());
    assertEquals(OrderStatus.PREPARING, finalOrder.getStatus());

    // No exception should have been thrown
    // No duplicate assignments should have occurred
  }
}
