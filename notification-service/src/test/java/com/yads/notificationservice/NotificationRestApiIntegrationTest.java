package com.yads.notificationservice;

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

/**
 * Integration tests for Notification REST API endpoints.
 * Tests authentication, authorization, and endpoint behavior.
 */
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationRestApiIntegrationTest extends AbstractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private NotificationRepository notificationRepository;

        private UUID userId;
        private UUID otherUserId;
        private Notification notification1;
        private Notification notification2;

        @AfterEach
        void cleanup() {
                notificationRepository.deleteAll();
        }

        @BeforeEach
        void setUp() {
                userId = UUID.randomUUID();
                otherUserId = UUID.randomUUID();

                // Create unread notification
                notification1 = new Notification();
                notification1.setUserId(userId);
                notification1.setType(NotificationType.ORDER_CREATED);
                notification1.setMessage("Test message 1");
                notification1.setOrderId(UUID.randomUUID());
                notification1.setIsRead(false);
                notification1.setPayload("{}");

                // Create read notification
                notification2 = new Notification();
                notification2.setUserId(userId);
                notification2.setType(NotificationType.ORDER_PREPARING);
                notification2.setMessage("Test message 2");
                notification2.setOrderId(UUID.randomUUID());
                notification2.setIsRead(true);
                notification2.setPayload("{}");

                notificationRepository.save(notification1);
                notificationRepository.save(notification2);
        }

        @Test
        @Order(1)
        void should_get_unread_notifications_with_jwt_authentication() throws Exception {
                mockMvc.perform(get("/api/v1/notifications/unread")
                                .with(jwt().jwt(builder -> builder
                                                .subject(userId.toString())
                                                .claim("email", "test@example.com"))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.length()").value(1))
                                .andExpect(jsonPath("$[0].type").value("ORDER_CREATED"))
                                .andExpect(jsonPath("$[0].isRead").value(false));
        }

        @Test
        @Order(2)
        void should_return_empty_list_when_no_unread_notifications() throws Exception {
                mockMvc.perform(get("/api/v1/notifications/unread")
                                .with(jwt().jwt(builder -> builder
                                                .subject(otherUserId.toString())
                                                .claim("email", "other@example.com"))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @Order(3)
        void should_get_notification_history_with_pagination() throws Exception {
                mockMvc.perform(get("/api/v1/notifications/history")
                                .param("page", "0")
                                .param("size", "10")
                                .with(jwt().jwt(builder -> builder
                                                .subject(userId.toString())
                                                .claim("email", "test@example.com"))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content.length()").value(2))
                                .andExpect(jsonPath("$.totalElements").value(2))
                                .andExpect(jsonPath("$.size").value(10));
        }

        @Test
        @Order(4)
        void should_mark_notification_as_read() throws Exception {
                mockMvc.perform(put("/api/v1/notifications/" + notification1.getId() + "/read")
                                .with(jwt().jwt(builder -> builder
                                                .subject(userId.toString())
                                                .claim("email", "test@example.com"))))
                                .andExpect(status().isOk());

                // Verify in database
                var updated = notificationRepository.findById(notification1.getId()).orElseThrow();
                assertThat(updated.getIsRead()).isTrue();
        }

        @Test
        @Order(5)
        void should_fail_marking_notification_as_read_for_different_user() throws Exception {
                mockMvc.perform(put("/api/v1/notifications/" + notification1.getId() + "/read")
                                .with(jwt().jwt(builder -> builder
                                                .subject(otherUserId.toString())
                                                .claim("email", "other@example.com"))))
                                .andExpect(status().isBadRequest());

                // Verify notification still unread
                var unchanged = notificationRepository.findById(notification1.getId()).orElseThrow();
                assertThat(unchanged.getIsRead()).isFalse();
        }

        @Test
        @Order(6)
        void should_fail_marking_non_existent_notification() throws Exception {
                UUID nonExistentId = UUID.randomUUID();

                mockMvc.perform(put("/api/v1/notifications/" + nonExistentId + "/read")
                                .with(jwt().jwt(builder -> builder
                                                .subject(userId.toString())
                                                .claim("email", "test@example.com"))))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @Order(7)
        void should_require_authentication_for_unread_endpoint() throws Exception {
                mockMvc.perform(get("/api/v1/notifications/unread"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @Order(8)
        void should_require_authentication_for_history_endpoint() throws Exception {
                mockMvc.perform(get("/api/v1/notifications/history"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @Order(9)
        void should_order_history_by_created_at_desc() throws Exception {
                // Create third notification with known timing
                Notification notification3 = new Notification();
                notification3.setUserId(userId);
                notification3.setType(NotificationType.ORDER_DELIVERED);
                notification3.setMessage("Latest notification");
                notification3.setOrderId(UUID.randomUUID());
                notification3.setIsRead(false);
                notification3.setPayload("{}");
                notificationRepository.save(notification3);

                mockMvc.perform(get("/api/v1/notifications/history")
                                .param("page", "0")
                                .param("size", "10")
                                .with(jwt().jwt(builder -> builder
                                                .subject(userId.toString()))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].message").value("Latest notification"));
        }

        @Test
        @Order(10)
        void should_support_custom_page_size() throws Exception {
                mockMvc.perform(get("/api/v1/notifications/history")
                                .param("page", "0")
                                .param("size", "1")
                                .with(jwt().jwt(builder -> builder
                                                .subject(userId.toString()))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content.length()").value(1))
                                .andExpect(jsonPath("$.size").value(1))
                                .andExpect(jsonPath("$.totalElements").value(2));
        }
}
