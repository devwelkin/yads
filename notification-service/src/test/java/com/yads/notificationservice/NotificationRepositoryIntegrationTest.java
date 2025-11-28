package com.yads.notificationservice;

import com.yads.notificationservice.model.Notification;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for NotificationRepository custom query methods.
 * Tests repository methods, pagination, and database operations.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    private UUID userId;
    private Notification notification1;
    private Notification notification2;
    private Notification notification3;

    @AfterEach
    void cleanup() {
        notificationRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        // Pending notification (not delivered)
        notification1 = new Notification();
        notification1.setUserId(userId);
        notification1.setType(NotificationType.ORDER_CREATED);
        notification1.setMessage("Message 1");
        notification1.setOrderId(UUID.randomUUID());
        notification1.setIsRead(false);
        notification1.setDeliveredAt(null); // Pending
        notification1.setPayload("{}");

        // Delivered and read notification
        notification2 = new Notification();
        notification2.setUserId(userId);
        notification2.setType(NotificationType.ORDER_PREPARING);
        notification2.setMessage("Message 2");
        notification2.setOrderId(UUID.randomUUID());
        notification2.setIsRead(true);
        notification2.setDeliveredAt(Instant.now());
        notification2.setPayload("{}");

        // Delivered but unread notification
        notification3 = new Notification();
        notification3.setUserId(userId);
        notification3.setType(NotificationType.ORDER_DELIVERED);
        notification3.setMessage("Message 3");
        notification3.setOrderId(UUID.randomUUID());
        notification3.setIsRead(false);
        notification3.setDeliveredAt(Instant.now());
        notification3.setPayload("{}");

        notificationRepository.save(notification1);
        notificationRepository.save(notification2);
        notificationRepository.save(notification3);
    }

    @Test
    @Order(1)
    void should_find_unread_notifications_ordered_by_created_at_desc() {
        var unread = notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId);

        assertThat(unread).hasSize(2);
        assertThat(unread.get(0).getMessage()).isEqualTo("Message 3"); // Latest
        assertThat(unread.get(1).getMessage()).isEqualTo("Message 1");
    }

    @Test
    @Order(2)
    void should_find_pending_notifications_where_delivered_at_is_null() {
        var pending = notificationRepository.findByUserIdAndDeliveredAtIsNullOrderByCreatedAtAsc(userId);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getMessage()).isEqualTo("Message 1");
        assertThat(pending.get(0).getDeliveredAt()).isNull();
    }

    @Test
    @Order(3)
    void should_support_pagination_ordered_by_created_at_desc() {
        var page = notificationRepository.findByUserIdOrderByCreatedAtDesc(
                userId,
                PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getMessage()).isEqualTo("Message 3"); // Latest
    }

    @Test
    @Order(4)
    void should_return_empty_list_for_different_user() {
        UUID differentUserId = UUID.randomUUID();

        var unread = notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(differentUserId);
        assertThat(unread).isEmpty();
    }

    @Test
    @Order(5)
    void should_persist_all_notification_fields_correctly() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(NotificationType.ORDER_ASSIGNED);
        notification.setMessage("Full test message");
        notification.setOrderId(orderId);
        notification.setStoreId(storeId);
        notification.setCourierId(courierId);
        notification.setIsRead(false);
        notification.setDeliveredAt(null);
        notification.setPayload("{\"test\": \"data\"}");

        var saved = notificationRepository.save(notification);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getType()).isEqualTo(NotificationType.ORDER_ASSIGNED);
        assertThat(saved.getOrderId()).isEqualTo(orderId);
        assertThat(saved.getStoreId()).isEqualTo(storeId);
        assertThat(saved.getCourierId()).isEqualTo(courierId);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @Order(6)
    void should_update_delivered_at_timestamp() {
        notification1.setDeliveredAt(Instant.now());
        notificationRepository.save(notification1);

        var updated = notificationRepository.findById(notification1.getId()).orElseThrow();
        assertThat(updated.getDeliveredAt()).isNotNull();
    }

    @Test
    @Order(7)
    void should_update_is_read_status() {
        notification1.setIsRead(true);
        notificationRepository.save(notification1);

        var updated = notificationRepository.findById(notification1.getId()).orElseThrow();
        assertThat(updated.getIsRead()).isTrue();
    }
}
