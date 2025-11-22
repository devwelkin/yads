package com.yads.notificationservice.subscriber;

import com.yads.common.contracts.*;
import com.yads.common.model.Address;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

  @InjectMocks
  private OrderEventSubscriber orderEventSubscriber;

  private UUID userId;
  private UUID orderId;
  private UUID storeId;
  private UUID courierId;
  private Address address;

  @BeforeEach
  void setUp() {
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

  @Test
  void handleOrderStatusChange_OrderCreatedRoutingKey_ProcessesOrderCreated() {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setStoreId(storeId);
    contract.setTotalPrice(BigDecimal.valueOf(100.0));
    contract.setCreatedAt(Instant.now());
    contract.setPickupAddress(address);
    contract.setShippingAddress(address);

    // Act
    orderEventSubscriber.handleOrderStatusChange(contract, "order.created");

    // Assert
    verify(notificationService).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_CREATED),
        anyString(),
        eq(orderId),
        eq(storeId),
        isNull(),
        eq(contract));
  }

  @Test
  void handleOrderStatusChange_OrderOnTheWayRoutingKey_ProcessesOrderOnTheWay() {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(courierId);

    // Act
    orderEventSubscriber.handleOrderStatusChange(contract, "order.on_the_way");

    // Assert
    verify(notificationService).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_ON_THE_WAY),
        anyString(),
        eq(orderId),
        isNull(),
        eq(courierId),
        eq(contract));
  }

  @Test
  void handleOrderStatusChange_OrderDeliveredRoutingKey_ProcessesOrderDelivered() {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(courierId);

    // Act
    orderEventSubscriber.handleOrderStatusChange(contract, "order.delivered");

    // Assert
    // Should send 2 notifications: customer + courier
    verify(notificationService, times(2)).createAndSendNotification(
        any(UUID.class),
        eq(NotificationType.ORDER_DELIVERED),
        anyString(),
        eq(orderId),
        isNull(),
        eq(courierId),
        eq(contract));
  }

  @Test
  void handleOrderStatusChange_UnknownRoutingKey_LogsWarning() {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);

    // Act
    orderEventSubscriber.handleOrderStatusChange(contract, "order.unknown");

    // Assert - should not throw, just log
    verify(notificationService, never()).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void handleOrderPreparing_ValidContract_SendsNotificationToCustomer() {
    // Arrange
    OrderAssignmentContract contract = new OrderAssignmentContract();
    contract.setOrderId(orderId);
    contract.setStoreId(storeId);
    contract.setUserId(userId);
    contract.setPickupAddress(address);
    contract.setShippingAddress(address);

    // Act
    orderEventSubscriber.handleOrderPreparing(contract);

    // Assert
    verify(notificationService).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_PREPARING),
        anyString(),
        eq(orderId),
        eq(storeId),
        isNull(),
        eq(contract));
  }

  @Test
  void handleOrderPreparing_ServiceThrowsException_CatchesAndLogs() {
    // Arrange
    OrderAssignmentContract contract = new OrderAssignmentContract();
    contract.setOrderId(orderId);
    contract.setStoreId(storeId);
    contract.setUserId(userId);

    doThrow(new RuntimeException("Service error"))
        .when(notificationService).createAndSendNotification(
            any(), any(), anyString(), any(), any(), any(), any());

    // Act - should not throw
    orderEventSubscriber.handleOrderPreparing(contract);

    // Assert
    verify(notificationService).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void handleOrderAssigned_ValidContract_SendsNotificationToCourierAndCustomer() {
    // Arrange
    OrderAssignedContract contract = new OrderAssignedContract();
    contract.setOrderId(orderId);
    contract.setStoreId(storeId);
    contract.setCourierId(courierId);
    contract.setUserId(userId);
    contract.setPickupAddress(address);
    contract.setShippingAddress(address);

    // Act
    orderEventSubscriber.handleOrderAssigned(contract);

    // Assert - should send 2 notifications
    verify(notificationService, times(2)).createAndSendNotification(
        any(UUID.class),
        eq(NotificationType.ORDER_ASSIGNED),
        anyString(),
        eq(orderId),
        eq(storeId),
        eq(courierId),
        eq(contract));

    // Verify courier notification
    verify(notificationService).createAndSendNotification(
        eq(courierId),
        eq(NotificationType.ORDER_ASSIGNED),
        contains("assigned to deliver"),
        eq(orderId),
        eq(storeId),
        eq(courierId),
        eq(contract));

    // Verify customer notification
    verify(notificationService).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_ASSIGNED),
        contains("courier has been assigned"),
        eq(orderId),
        eq(storeId),
        eq(courierId),
        eq(contract));
  }

  @Test
  void handleOrderCancelled_WithCourier_SendsNotificationToCustomerAndCourier() {
    // Arrange
    OrderCancelledContract contract = new OrderCancelledContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(courierId);
    contract.setStoreId(storeId);

    // Act
    orderEventSubscriber.handleOrderCancelled(contract);

    // Assert - should send 2 notifications
    verify(notificationService, times(2)).createAndSendNotification(
        any(UUID.class),
        eq(NotificationType.ORDER_CANCELLED),
        anyString(),
        eq(orderId),
        eq(storeId),
        eq(courierId),
        eq(contract));
  }

  @Test
  void handleOrderCancelled_WithoutCourier_SendsNotificationOnlyToCustomer() {
    // Arrange
    OrderCancelledContract contract = new OrderCancelledContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(null); // No courier assigned
    contract.setStoreId(storeId);

    // Act
    orderEventSubscriber.handleOrderCancelled(contract);

    // Assert - should send only 1 notification to customer
    verify(notificationService, times(1)).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_CANCELLED),
        anyString(),
        eq(orderId),
        eq(storeId),
        isNull(),
        eq(contract));
  }

  @Test
  void handleOrderDelivered_WithoutCourier_SendsOnlyCustomerNotification() {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setCourierId(null); // No courier

    // Act
    orderEventSubscriber.handleOrderStatusChange(contract, "order.delivered");

    // Assert - should send only 1 notification to customer
    verify(notificationService, times(1)).createAndSendNotification(
        eq(userId),
        eq(NotificationType.ORDER_DELIVERED),
        anyString(),
        eq(orderId),
        isNull(),
        isNull(),
        eq(contract));
  }

  @Test
  void handleOrderCreated_ServiceThrowsException_CatchesAndLogs() {
    // Arrange
    OrderStatusChangeContract contract = new OrderStatusChangeContract();
    contract.setOrderId(orderId);
    contract.setUserId(userId);
    contract.setStoreId(storeId);

    doThrow(new RuntimeException("Service error"))
        .when(notificationService).createAndSendNotification(
            any(), any(), anyString(), any(), any(), any(), any());

    // Act - should not throw
    orderEventSubscriber.handleOrderStatusChange(contract, "order.created");

    // Assert
    verify(notificationService).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void handleOrderAssigned_PartialFailure_ContinuesProcessing() {
    // Arrange
    OrderAssignedContract contract = new OrderAssignedContract();
    contract.setOrderId(orderId);
    contract.setStoreId(storeId);
    contract.setCourierId(courierId);
    contract.setUserId(userId);

    // First call (courier) succeeds, second call (customer) fails
    doNothing()
        .doThrow(new RuntimeException("Customer notification failed"))
        .when(notificationService).createAndSendNotification(
            any(), any(), anyString(), any(), any(), any(), any());

    // Act - should not throw
    orderEventSubscriber.handleOrderAssigned(contract);

    // Assert - both notifications attempted
    verify(notificationService, times(2)).createAndSendNotification(
        any(), any(), anyString(), any(), any(), any(), any());
  }
}
