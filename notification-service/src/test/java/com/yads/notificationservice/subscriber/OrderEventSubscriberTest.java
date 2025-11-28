package com.yads.notificationservice.subscriber;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.common.contracts.*;
import com.yads.common.model.Address;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderEventSubscriber.
 * Tests routing key dispatch logic and error handling.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventSubscriberTest {

  @Mock
  private NotificationService notificationService;

  private ObjectMapper objectMapper;
  private OrderEventSubscriber orderEventSubscriber;

  private UUID userId;
  private UUID orderId;
  private UUID storeId;
  private UUID courierId;
  private Address address;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.findAndRegisterModules(); // For Instant serialization
    orderEventSubscriber = new OrderEventSubscriber(notificationService, objectMapper);

    userId = UUID.randomUUID();
    orderId = UUID.randomUUID();
    storeId = UUID.randomUUID();
    courierId = UUID.randomUUID();

    address = new Address();
    address.setStreet("Test Street 123");
    address.setCity("Test City");
    address.setPostalCode("12345");
    address.setCountry("Turkey");
  }

  /**
   * Helper to create a raw Message like RabbitMQ would deliver.
   */
  private Message createMessage(Object payload, String routingKey) throws Exception {
    String json = objectMapper.writeValueAsString(payload);
    MessageProperties props = new MessageProperties();
    props.setReceivedRoutingKey(routingKey);
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    return new Message(json.getBytes(StandardCharsets.UTF_8), props);
  }

  @Test
  void handleRawMessage_OrderCreatedRoutingKey_ProcessesOrderCreated() throws Exception {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setStoreId(storeId);
    contract.setTotalPrice(BigDecimal.valueOf(100.0));
    contract.setCreatedAt(Instant.now());
    contract.setPickupAddress(address);
    contract.setShippingAddress(address);

    Message message = createMessage(contract, "order.created");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert
    verify(notificationService).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_CREATED),
        anyString(),
        eq(orderId),
        eq(storeId),
        isNull(),
        any(OrderStatusChangeContract.class));
  }

  @Test
  void handleRawMessage_OrderOnTheWayRoutingKey_ProcessesOrderOnTheWay() throws Exception {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(courierId);

    Message message = createMessage(contract, "order.on_the_way");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert
    verify(notificationService).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_ON_THE_WAY),
        anyString(),
        eq(orderId),
        isNull(),
        eq(courierId),
        any(OrderStatusChangeContract.class));
  }

  @Test
  void handleRawMessage_OrderDeliveredRoutingKey_ProcessesOrderDelivered() throws Exception {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(courierId);

    Message message = createMessage(contract, "order.delivered");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert
    // Should send 2 notifications: customer + courier
    verify(notificationService, times(2)).createAndSendNotification(
        any(UUID.class),
        eq(NotificationType.ORDER_DELIVERED),
        anyString(),
        eq(orderId),
        isNull(),
        eq(courierId),
        any(OrderStatusChangeContract.class));
  }

  @Test
  void handleRawMessage_UnknownRoutingKey_LogsWarningNoNotification() throws Exception {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);

    Message message = createMessage(contract, "order.unknown");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert - should not throw, just log
    verify(notificationService, never()).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void handleRawMessage_OrderPreparing_SendsNotificationToCustomer() throws Exception {
    // Arrange
    OrderAssignmentContract contract = new OrderAssignmentContract();
    contract.setOrderId(orderId);
    contract.setStoreId(storeId);
    contract.setUserId(userId);
    contract.setPickupAddress(address);
    contract.setShippingAddress(address);

    Message message = createMessage(contract, "order.preparing");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert
    verify(notificationService).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_PREPARING),
        anyString(),
        eq(orderId),
        eq(storeId),
        isNull(),
        any(OrderAssignmentContract.class));
  }

  @Test
  void handleRawMessage_OrderPreparing_ServiceThrowsException_CatchesAndLogs() throws Exception {
    // Arrange
    OrderAssignmentContract contract = new OrderAssignmentContract();
    contract.setOrderId(orderId);
    contract.setStoreId(storeId);
    contract.setUserId(userId);

    doThrow(new RuntimeException("Service error"))
        .when(notificationService).createAndSendNotification(
            any(), any(), anyString(), any(), any(), any(), any());

    Message message = createMessage(contract, "order.preparing");

    // Act - should not throw
    orderEventSubscriber.handleRawMessage(message);

    // Assert
    verify(notificationService).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void handleRawMessage_OrderAssigned_SendsNotificationToCourierAndCustomer() throws Exception {
    // Arrange
    OrderAssignedContract contract = new OrderAssignedContract();
    contract.setOrderId(orderId);
    contract.setStoreId(storeId);
    contract.setCourierId(courierId);
    contract.setUserId(userId);
    contract.setPickupAddress(address);
    contract.setShippingAddress(address);

    Message message = createMessage(contract, "order.assigned");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert - should send 2 notifications
    verify(notificationService, times(2)).createAndSendNotification(
        any(UUID.class),
        eq(NotificationType.ORDER_ASSIGNED),
        anyString(),
        eq(orderId),
        eq(storeId),
        eq(courierId),
        any(OrderAssignedContract.class));

    // Verify courier notification
    verify(notificationService).createAndSendNotification(
        eq(courierId),
        eq(NotificationType.ORDER_ASSIGNED),
        contains("assigned to deliver"),
        eq(orderId),
        eq(storeId),
        eq(courierId),
        any(OrderAssignedContract.class));

    // Verify customer notification
    verify(notificationService).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_ASSIGNED),
        contains("courier has been assigned"),
        eq(orderId),
        eq(storeId),
        eq(courierId),
        any(OrderAssignedContract.class));
  }

  @Test
  void handleRawMessage_OrderCancelled_WithCourier_SendsNotificationToCustomerAndCourier() throws Exception {
    // Arrange
    OrderCancelledContract contract = new OrderCancelledContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(courierId);
    contract.setStoreId(storeId);

    Message message = createMessage(contract, "order.cancelled");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert - should send 2 notifications
    verify(notificationService, times(2)).createAndSendNotification(
        any(UUID.class),
        eq(NotificationType.ORDER_CANCELLED),
        anyString(),
        eq(orderId),
        eq(storeId),
        eq(courierId),
        any(OrderCancelledContract.class));
  }

  @Test
  void handleRawMessage_OrderCancelled_WithoutCourier_SendsNotificationOnlyToCustomer() throws Exception {
    // Arrange
    OrderCancelledContract contract = new OrderCancelledContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(null); // No courier assigned
    contract.setStoreId(storeId);

    Message message = createMessage(contract, "order.cancelled");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert - should send only 1 notification to customer
    verify(notificationService, times(1)).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_CANCELLED),
        anyString(),
        eq(orderId),
        eq(storeId),
        isNull(),
        any(OrderCancelledContract.class));
  }

  @Test
  void handleRawMessage_OrderDelivered_WithoutCourier_SendsOnlyCustomerNotification() throws Exception {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(null); // No courier

    Message message = createMessage(contract, "order.delivered");

    // Act
    orderEventSubscriber.handleRawMessage(message);

    // Assert - should send only 1 notification to customer
    verify(notificationService, times(1)).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_DELIVERED),
        anyString(),
        eq(orderId),
        isNull(),
        isNull(),
        any(OrderStatusChangeContract.class));
  }

  @Test
  void handleRawMessage_OrderCreated_ServiceThrowsException_CatchesAndLogs() throws Exception {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setStoreId(storeId);

    doThrow(new RuntimeException("Service error"))
        .when(notificationService).createAndSendNotification(
            any(), any(), anyString(), any(), any(), any(), any());

    Message message = createMessage(contract, "order.created");

    // Act - should not throw
    orderEventSubscriber.handleRawMessage(message);

    // Assert
    verify(notificationService).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void handleRawMessage_InvalidJson_LogsErrorDoesNotThrow() {
    // Arrange
    MessageProperties props = new MessageProperties();
    props.setReceivedRoutingKey("order.created");
    Message message = new Message("invalid json".getBytes(StandardCharsets.UTF_8), props);

    // Act - should not throw
    orderEventSubscriber.handleRawMessage(message);

    // Assert
    verify(notificationService, never()).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void handleRawMessage_NullRoutingKey_LogsWarning() throws Exception {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);

    String json = objectMapper.writeValueAsString(contract);
    MessageProperties props = new MessageProperties();
    props.setReceivedRoutingKey(null);
    Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);

    // Act - should not throw
    orderEventSubscriber.handleRawMessage(message);

    // Assert
    verify(notificationService, never()).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }
}
