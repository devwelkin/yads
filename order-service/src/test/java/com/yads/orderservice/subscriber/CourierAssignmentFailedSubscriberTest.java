package com.yads.orderservice.subscriber;

import com.yads.common.contracts.CourierAssignmentFailedContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderItem;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourierAssignmentFailedSubscriberTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private RabbitTemplate rabbitTemplate;

  @InjectMocks
  private CourierAssignmentFailedSubscriber subscriber;

  private UUID orderId;
  private UUID userId;
  private UUID storeId;
  private Order preparingOrder;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    userId = UUID.randomUUID();
    storeId = UUID.randomUUID();

    preparingOrder = new Order();
    preparingOrder.setId(orderId);
    preparingOrder.setUserId(userId);
    preparingOrder.setStoreId(storeId);
    preparingOrder.setStatus(OrderStatus.PREPARING);
    preparingOrder.setTotalPrice(BigDecimal.valueOf(100));

    OrderItem item = new OrderItem();
    item.setProductId(UUID.randomUUID());
    item.setProductName("Test Product");
    item.setQuantity(3);
    item.setPrice(BigDecimal.valueOf(33.33));
    item.setOrder(preparingOrder);

    List<OrderItem> items = new ArrayList<>();
    items.add(item);
    preparingOrder.setItems(items);
  }

  // --- HAPPY PATH ---

  @Test
  void should_cancel_order_and_publish_cancellation_event_with_stock_restoration() {
    // ARRANGE
    CourierAssignmentFailedContract contract = CourierAssignmentFailedContract.builder()
        .orderId(orderId)
        .userId(userId)
        .reason("No couriers available in the area")
        .build();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(preparingOrder));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

    // ACT
    subscriber.handleCourierAssignmentFailed(contract);

    // ASSERT: Order status should be CANCELLED
    verify(orderRepository).save(argThat(order -> order.getStatus() == OrderStatus.CANCELLED &&
        order.getId().equals(orderId)));

    // ASSERT: Cancellation event should be published with oldStatus=PREPARING
    ArgumentCaptor<OrderCancelledContract> eventCaptor = ArgumentCaptor.forClass(OrderCancelledContract.class);
    verify(rabbitTemplate).convertAndSend(
        eq("order_events_exchange"),
        eq("order.cancelled"),
        eventCaptor.capture());

    OrderCancelledContract publishedEvent = eventCaptor.getValue();
    assertEquals(orderId, publishedEvent.getOrderId());
    assertEquals(userId, publishedEvent.getUserId());
    assertEquals(storeId, publishedEvent.getStoreId());
    assertEquals("PREPARING", publishedEvent.getOldStatus(),
        "oldStatus must be PREPARING to trigger stock restoration");

    // ASSERT: Items should be included for stock restoration
    assertNotNull(publishedEvent.getItems());
    assertEquals(1, publishedEvent.getItems().size());
    assertEquals(preparingOrder.getItems().get(0).getProductId(),
        publishedEvent.getItems().get(0).getProductId());
    assertEquals(preparingOrder.getItems().get(0).getQuantity(),
        publishedEvent.getItems().get(0).getQuantity());
  }

  // --- IDEMPOTENCY: Already Cancelled ---

  @Test
  void should_ignore_event_if_order_already_cancelled() {
    // ARRANGE
    preparingOrder.setStatus(OrderStatus.CANCELLED);

    CourierAssignmentFailedContract contract = CourierAssignmentFailedContract.builder()
        .orderId(orderId)
        .userId(userId)
        .reason("No couriers available")
        .build();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(preparingOrder));

    // ACT
    subscriber.handleCourierAssignmentFailed(contract);

    // ASSERT: No save or event publishing should occur
    verify(orderRepository, never()).save(any());
    verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
  }

  // --- IDEMPOTENCY: Order is DELIVERED ---

  @Test
  void should_ignore_event_if_order_already_delivered() {
    // ARRANGE
    preparingOrder.setStatus(OrderStatus.DELIVERED);

    CourierAssignmentFailedContract contract = CourierAssignmentFailedContract.builder()
        .orderId(orderId)
        .userId(userId)
        .reason("No couriers available")
        .build();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(preparingOrder));

    // ACT
    subscriber.handleCourierAssignmentFailed(contract);

    // ASSERT: Should be ignored (idempotency)
    verify(orderRepository, never()).save(any());
    verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
  }

  // --- EDGE CASE: Order in PENDING state (should not happen but defensive) ---

  @Test
  void should_ignore_event_if_order_is_pending() {
    // ARRANGE
    preparingOrder.setStatus(OrderStatus.PENDING);

    CourierAssignmentFailedContract contract = CourierAssignmentFailedContract.builder()
        .orderId(orderId)
        .userId(userId)
        .reason("No couriers available")
        .build();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(preparingOrder));

    // ACT
    subscriber.handleCourierAssignmentFailed(contract);

    // ASSERT: Should be ignored (invalid state for courier assignment failure)
    verify(orderRepository, never()).save(any());
    verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
  }

  // --- ERROR HANDLING: Order Not Found ---

  @Test
  void should_throw_exception_when_order_not_found() {
    // ARRANGE
    CourierAssignmentFailedContract contract = CourierAssignmentFailedContract.builder()
        .orderId(orderId)
        .userId(userId)
        .reason("No couriers available")
        .build();

    when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

    // ACT & ASSERT
    assertThrows(ResourceNotFoundException.class, () -> subscriber.handleCourierAssignmentFailed(contract));

    verify(orderRepository, never()).save(any());
    verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
  }

  // --- ERROR HANDLING: RabbitMQ Publishing Fails ---

  @Test
  void should_save_order_even_if_event_publishing_fails() {
    // ARRANGE
    CourierAssignmentFailedContract contract = CourierAssignmentFailedContract.builder()
        .orderId(orderId)
        .userId(userId)
        .reason("No couriers available")
        .build();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(preparingOrder));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

    // RabbitMQ publishing fails
    doThrow(new RuntimeException("RabbitMQ connection lost"))
        .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

    // ACT - should not throw exception
    assertDoesNotThrow(() -> subscriber.handleCourierAssignmentFailed(contract));

    // ASSERT: Order should still be saved as CANCELLED
    verify(orderRepository).save(argThat(order -> order.getStatus() == OrderStatus.CANCELLED));
  }

  // --- CRITICAL: Stock Restoration Items Validation ---

  @Test
  void should_include_all_order_items_for_stock_restoration() {
    // ARRANGE: Order with multiple items
    OrderItem item2 = new OrderItem();
    item2.setProductId(UUID.randomUUID());
    item2.setProductName("Second Product");
    item2.setQuantity(5);
    item2.setPrice(BigDecimal.valueOf(20));
    item2.setOrder(preparingOrder);
    preparingOrder.getItems().add(item2);

    CourierAssignmentFailedContract contract = CourierAssignmentFailedContract.builder()
        .orderId(orderId)
        .userId(userId)
        .reason("No couriers available")
        .build();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(preparingOrder));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

    // ACT
    subscriber.handleCourierAssignmentFailed(contract);

    // ASSERT: All items should be in the cancellation event
    ArgumentCaptor<OrderCancelledContract> eventCaptor = ArgumentCaptor.forClass(OrderCancelledContract.class);
    verify(rabbitTemplate).convertAndSend(
        eq("order_events_exchange"),
        eq("order.cancelled"),
        eventCaptor.capture());

    OrderCancelledContract publishedEvent = eventCaptor.getValue();
    assertEquals(2, publishedEvent.getItems().size(),
        "All order items must be included for stock restoration");

    // Verify quantities match
    int totalQuantity = publishedEvent.getItems().stream()
        .mapToInt(item -> item.getQuantity())
        .sum();
    assertEquals(8, totalQuantity); // 3 + 5 = 8
  }
}
