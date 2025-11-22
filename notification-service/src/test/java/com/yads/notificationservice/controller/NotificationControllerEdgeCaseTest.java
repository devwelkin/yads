package com.yads.notificationservice.controller;

import com.yads.notificationservice.model.Notification;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.yads.notificationservice.AbstractIntegrationTest;

/**
 * Additional edge case tests for NotificationController.
 * Tests invalid inputs, error scenarios, and boundary conditions.
 */
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationControllerEdgeCaseTest extends AbstractIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private NotificationRepository notificationRepository;

  private UUID userId;

  @AfterEach
  void cleanup() {
    notificationRepository.deleteAll();
  }

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
  }

  @Test
  @Order(1)
  void should_return_401_when_no_authentication_provided() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/unread"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(2)
  void should_return_401_for_history_without_authentication() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/history"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(3)
  void should_return_401_for_mark_as_read_without_authentication() throws Exception {
    UUID notificationId = UUID.randomUUID();
    mockMvc.perform(put("/api/v1/notifications/" + notificationId + "/read"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(4)
  void should_handle_invalid_uuid_format_in_mark_as_read() throws Exception {
    mockMvc.perform(put("/api/v1/notifications/invalid-uuid/read")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(5)
  void should_handle_negative_page_number() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/history")
        .param("page", "-1")
        .param("size", "10")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().is5xxServerError()); // Spring throws exception for negative page
  }

  @Test
  @Order(6)
  void should_handle_zero_page_size() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/history")
        .param("page", "0")
        .param("size", "0")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().is5xxServerError()); // Invalid page size
  }

  @Test
  @Order(7)
  void should_handle_negative_page_size() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/history")
        .param("page", "0")
        .param("size", "-5")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().is5xxServerError());
  }

  @Test
  @Order(8)
  void should_handle_very_large_page_size() throws Exception {
    // Some systems might have max page size limits
    mockMvc.perform(get("/api/v1/notifications/history")
        .param("page", "0")
        .param("size", "100000")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray());
  }

  @Test
  @Order(9)
  void should_handle_very_large_page_number() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/history")
        .param("page", "999999")
        .param("size", "10")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty());
  }

  @Test
  @Order(10)
  void should_handle_malformed_jwt_token() throws Exception {
    // This is handled by Spring Security's OAuth2 resource server
    mockMvc.perform(get("/api/v1/notifications/unread")
        .header("Authorization", "Bearer invalid.malformed.token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(11)
  void should_handle_missing_bearer_prefix() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/unread")
        .header("Authorization", "some-token-without-bearer"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(12)
  void should_return_empty_list_for_user_with_no_notifications() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/unread")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @Order(13)
  void should_return_empty_page_for_history_with_no_notifications() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/history")
        .param("page", "0")
        .param("size", "10")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  @Order(14)
  void should_not_allow_user_to_mark_another_users_notification_as_read() throws Exception {
    // Create notification for user1
    UUID user1Id = UUID.randomUUID();
    Notification notification = createAndSaveNotification(user1Id);

    // Try to mark as read with user2's token
    UUID user2Id = UUID.randomUUID();
    mockMvc.perform(put("/api/v1/notifications/" + notification.getId() + "/read")
        .with(jwt().jwt(builder -> builder.subject(user2Id.toString()))))
        .andExpect(status().isBadRequest());

    // Verify notification is still unread
    Notification unchanged = notificationRepository.findById(notification.getId()).orElseThrow();
    assertThat(unchanged.getIsRead()).isFalse();
  }

  @Test
  @Order(15)
  void should_handle_double_marking_notification_as_read() throws Exception {
    // Create notification
    Notification notification = createAndSaveNotification(userId);

    // Mark as read first time
    mockMvc.perform(put("/api/v1/notifications/" + notification.getId() + "/read")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk());

    // Mark as read second time (idempotent)
    mockMvc.perform(put("/api/v1/notifications/" + notification.getId() + "/read")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk());

    // Verify still read
    Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
    assertThat(updated.getIsRead()).isTrue();
  }

  @Test
  @Order(16)
  void should_handle_non_existent_notification_id() throws Exception {
    UUID nonExistentId = UUID.randomUUID();

    mockMvc.perform(put("/api/v1/notifications/" + nonExistentId + "/read")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(17)
  void should_handle_concurrent_requests_for_same_user() throws Exception {
    // Create multiple notifications
    for (int i = 0; i < 5; i++) {
      createAndSaveNotification(userId);
    }

    // Make concurrent requests (simulated sequentially here)
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(get("/api/v1/notifications/unread")
          .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(5));
    }
  }

  @Test
  @Order(18)
  void should_handle_invalid_page_parameter_type() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/history")
        .param("page", "abc")
        .param("size", "10")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(19)
  void should_handle_invalid_size_parameter_type() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/history")
        .param("page", "0")
        .param("size", "xyz")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(20)
  void should_use_default_pagination_when_parameters_not_provided() throws Exception {
    // Create some notifications
    for (int i = 0; i < 25; i++) {
      createAndSaveNotification(userId);
    }

    mockMvc.perform(get("/api/v1/notifications/history")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(20)) // Default size
        .andExpect(jsonPath("$.number").value(0)) // Default page
        .andExpect(jsonPath("$.content.length()").value(20));
  }

  @Test
  @Order(21)
  void should_handle_user_with_special_uuid() throws Exception {
    // Test with all zeros UUID
    UUID specialUserId = UUID.fromString("00000000-0000-0000-0000-000000000000");

    mockMvc.perform(get("/api/v1/notifications/unread")
        .with(jwt().jwt(builder -> builder.subject(specialUserId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  @Order(22)
  void should_properly_order_unread_notifications_by_creation_time() throws Exception {
    // Create notifications with slight delay to ensure different timestamps
    Notification notif1 = createAndSaveNotification(userId);
    Thread.sleep(10);
    createAndSaveNotification(userId); // notif2
    Thread.sleep(10);
    Notification notif3 = createAndSaveNotification(userId);

    mockMvc.perform(get("/api/v1/notifications/unread")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(notif3.getId().toString())) // Latest first
        .andExpect(jsonPath("$[2].id").value(notif1.getId().toString())); // Oldest last
  }

  @Test
  @Order(23)
  void should_handle_notification_with_null_optional_fields() throws Exception {
    Notification notification = new Notification();
    notification.setUserId(userId);
    notification.setType(NotificationType.ORDER_CREATED);
    notification.setOrderId(UUID.randomUUID());
    notification.setMessage("Test message");
    notification.setPayload("{}");
    notification.setIsRead(false);
    // storeId, courierId, deliveredAt are null
    notificationRepository.save(notification);

    mockMvc.perform(get("/api/v1/notifications/unread")
        .with(jwt().jwt(builder -> builder.subject(userId.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].storeId").doesNotExist())
        .andExpect(jsonPath("$[0].courierId").doesNotExist());
  }

  private Notification createAndSaveNotification(UUID userId) {
    Notification notification = new Notification();
    notification.setUserId(userId);
    notification.setType(NotificationType.ORDER_CREATED);
    notification.setOrderId(UUID.randomUUID());
    notification.setMessage("Test message");
    notification.setPayload("{}");
    notification.setIsRead(false);
    return notificationRepository.save(notification);
  }
}
