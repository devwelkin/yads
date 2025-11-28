package com.yads.notificationservice;

import com.yads.common.contracts.*;
import com.yads.common.model.Address;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for RabbitMQ event processing in NotificationService.
 * Tests all 6 order event types and verifies notifications are created
 * correctly.
 *
 * Now using consolidated handler approach: OrderStatusChangeContract events
 * (order.created, order.on_the_way, order.delivered) are dispatched via routing
 * key
 * to avoid Spring AMQP's "Ambiguous methods" error.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationEventProcessingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    private UUID userId;
    private UUID orderId;
    private UUID storeId;
    private UUID courierId;
    private Address address;

    @AfterEach
    void cleanup() {
        notificationRepository.deleteAll();
    }

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
    @Order(1)
    void should_create_notification_for_order_created_event() {
        // Arrange
        OrderStatusChangeContract event = new OrderStatusChangeContract();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setStoreId(storeId);
        event.setTotalPrice(BigDecimal.valueOf(100.0));
        event.setCreatedAt(Instant.now());
        event.setPickupAddress(address);
        event.setShippingAddress(address);

        // Act
        rabbitTemplate.convertAndSend("order_events_exchange", "order.created", event);

        // Assert
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);

            var notification = notifications.get(0);
            assertThat(notification.getUserId()).isEqualTo(userId);
            assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_CREATED);
            assertThat(notification.getOrderId()).isEqualTo(orderId);
            assertThat(notification.getStoreId()).isEqualTo(storeId);
            assertThat(notification.getCourierId()).isNull();
            assertThat(notification.getMessage()).isNotEmpty();
            assertThat(notification.getPayload()).isNotNull();
            assertThat(notification.getIsRead()).isFalse();
            assertThat(notification.getDeliveredAt()).isNull(); // User offline
        });
    }

    @Test
    @Order(2)
    void should_create_notification_for_order_preparing_event() {
        // Arrange
        OrderAssignmentContract event = new OrderAssignmentContract();
        event.setOrderId(orderId);
        event.setStoreId(storeId);
        event.setUserId(userId);
        event.setPickupAddress(address);
        event.setShippingAddress(address);

        // Act
        rabbitTemplate.convertAndSend("order_events_exchange", "order.preparing", event);

        // Assert
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);

            var notification = notifications.get(0);
            assertThat(notification.getUserId()).isEqualTo(userId);
            assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_PREPARING);
            assertThat(notification.getOrderId()).isEqualTo(orderId);
            assertThat(notification.getMessage()).isNotEmpty();
        });
    }

    @Test
    @Order(2)
    void should_create_notifications_for_courier_and_customer_on_order_assigned() {
        // Arrange
        OrderAssignedContract event = new OrderAssignedContract();
        event.setOrderId(orderId);
        event.setStoreId(storeId);
        event.setCourierId(courierId);
        event.setUserId(userId);
        event.setPickupAddress(address);
        event.setShippingAddress(address);

        // Act
        rabbitTemplate.convertAndSend("order_events_exchange", "order.assigned", event);

        // Assert
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(2);

            // Customer notification
            var customerNotif = notifications.stream()
                    .filter(n -> n.getUserId().equals(userId))
                    .findFirst()
                    .orElseThrow();
            assertThat(customerNotif.getType()).isEqualTo(NotificationType.ORDER_ASSIGNED);
            assertThat(customerNotif.getCourierId()).isEqualTo(courierId);

            // Courier notification
            var courierNotif = notifications.stream()
                    .filter(n -> n.getUserId().equals(courierId))
                    .findFirst()
                    .orElseThrow();
            assertThat(courierNotif.getType()).isEqualTo(NotificationType.ORDER_ASSIGNED);
            assertThat(courierNotif.getOrderId()).isEqualTo(orderId);
        });
    }

    @Test
    @Order(4)
    void should_create_notification_for_order_on_the_way_event() {
        // Arrange
        OrderStatusChangeContract event = new OrderStatusChangeContract();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setCourierId(courierId);

        // Act
        rabbitTemplate.convertAndSend("order_events_exchange", "order.on_the_way", event);

        // Assert
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);

            var notification = notifications.get(0);
            assertThat(notification.getUserId()).isEqualTo(userId);
            assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_ON_THE_WAY);
            assertThat(notification.getOrderId()).isEqualTo(orderId);
            assertThat(notification.getCourierId()).isEqualTo(courierId);
            assertThat(notification.getMessage()).isNotEmpty();
        });
    }

    @Test
    @Order(5)
    void should_create_notifications_for_customer_and_courier_on_order_delivered() {
        // Arrange
        OrderStatusChangeContract event = new OrderStatusChangeContract();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setCourierId(courierId);

        // Act
        rabbitTemplate.convertAndSend("order_events_exchange", "order.delivered", event);

        // Assert
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(2);

            var customerNotif = notifications.stream()
                    .filter(n -> n.getUserId().equals(userId))
                    .findFirst()
                    .orElseThrow();
            assertThat(customerNotif.getType()).isEqualTo(NotificationType.ORDER_DELIVERED);

            var courierNotif = notifications.stream()
                    .filter(n -> n.getUserId().equals(courierId))
                    .findFirst()
                    .orElseThrow();
            assertThat(courierNotif.getType()).isEqualTo(NotificationType.ORDER_DELIVERED);
        });
    }

    @Test
    @Order(6)
    void should_create_notifications_on_order_cancelled_with_courier() {
        // Arrange
        OrderCancelledContract event = new OrderCancelledContract();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setCourierId(courierId); // Courier assigned

        // Act
        rabbitTemplate.convertAndSend("order_events_exchange", "order.cancelled", event);

        // Assert
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(2); // Customer + Courier

            var userIds = notifications.stream()
                    .map(n -> n.getUserId())
                    .toList();
            assertThat(userIds).containsExactlyInAnyOrder(userId, courierId);
        });
    }

    @Test
    @Order(7)
    void should_create_notification_on_order_cancelled_without_courier() {
        // Arrange
        OrderCancelledContract event = new OrderCancelledContract();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setCourierId(null); // No courier assigned

        // Act
        rabbitTemplate.convertAndSend("order_events_exchange", "order.cancelled", event);

        // Assert
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1); // Only customer

            var notification = notifications.get(0);
            assertThat(notification.getUserId()).isEqualTo(userId);
            assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_CANCELLED);
        });
    }

    @Test
    @Order(8)
    void should_serialize_event_payload_to_json() {
        // Arrange
        OrderStatusChangeContract event = new OrderStatusChangeContract();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setStoreId(storeId);
        event.setTotalPrice(BigDecimal.valueOf(250.50));
        event.setCreatedAt(Instant.now());
        event.setPickupAddress(address);
        event.setShippingAddress(address);

        // Act
        rabbitTemplate.convertAndSend("order_events_exchange", "order.created", event);

        // Assert
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);

            var notification = notifications.get(0);
            var payload = notification.getPayload();

            assertThat(payload).isNotNull();
            assertThat(payload).contains(orderId.toString());
            assertThat(payload).contains(userId.toString());
            assertThat(payload).contains("250.5"); // JSON serializes as 250.5, not 250.50
            assertThat(payload).contains("Test Street 123");
        });
    }
}
