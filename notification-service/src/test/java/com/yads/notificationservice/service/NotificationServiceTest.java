package com.yads.notificationservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yads.notificationservice.dto.NotificationDto;
import com.yads.notificationservice.model.Notification;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private SimpUserRegistry userRegistry;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private NotificationService notificationService;

    private UUID userId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Repository save mock - return what is passed
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(i -> {
                    Notification n = i.getArgument(0);
                    if (n.getId() == null)
                        n.setId(UUID.randomUUID());
                    return n;
                });
    }

    @Test
    void createAndSend_UserOnline_SendsRealtime() {
        // Arrange
        when(userRegistry.getUser(userId.toString())).thenReturn(mock(SimpUser.class));

        // Act
        notificationService.createAndSendNotification(
                userId, NotificationType.ORDER_CREATED, "Test Msg",
                orderId, null, null, Map.of("key", "value"));

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());

        Notification finalState = captor.getAllValues().get(1);
        assertThat(finalState.getDeliveredAt()).isNotNull();

        verify(messagingTemplate).convertAndSendToUser(eq(userId.toString()), anyString(), any());
    }

    @Test
    void createAndSend_UserOffline_QueuesNotification() {
        // Arrange
        when(userRegistry.getUser(userId.toString())).thenReturn(null);

        // Act
        notificationService.createAndSendNotification(
                userId, NotificationType.ORDER_CREATED, "Test Msg",
                orderId, null, null, Map.of("key", "value"));

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getDeliveredAt()).isNull();

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void sendPendingNotifications_NoPendingNotifications_DoesNothing() {
        // Arrange
        when(notificationRepository.findByUserIdAndDeliveredAtIsNullOrderByCreatedAtAsc(userId))
                .thenReturn(List.of());

        // Act
        notificationService.sendPendingNotifications(userId);

        // Assert
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void sendPendingNotifications_WithPendingNotifications_SendsAllAndMarksDelivered() {
        // Arrange
        Notification notif1 = createNotification(userId, NotificationType.ORDER_CREATED);
        Notification notif2 = createNotification(userId, NotificationType.ORDER_PREPARING);

        when(notificationRepository.findByUserIdAndDeliveredAtIsNullOrderByCreatedAtAsc(userId))
                .thenReturn(List.of(notif1, notif2));

        // Act
        notificationService.sendPendingNotifications(userId);

        // Assert
        verify(messagingTemplate, times(2)).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/notifications"), any());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());

        List<Notification> savedNotifications = captor.getAllValues();
        assertThat(savedNotifications).allMatch(n -> n.getDeliveredAt() != null);
    }

    @Test
    void sendPendingNotifications_PartialFailure_ContinuesProcessing() {
        // Arrange
        Notification notif1 = createNotification(userId, NotificationType.ORDER_CREATED);
        Notification notif2 = createNotification(userId, NotificationType.ORDER_PREPARING);

        when(notificationRepository.findByUserIdAndDeliveredAtIsNullOrderByCreatedAtAsc(userId))
                .thenReturn(List.of(notif1, notif2));

        // First send fails, second succeeds
        doThrow(new RuntimeException("WebSocket error"))
                .doNothing()
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        // Act
        notificationService.sendPendingNotifications(userId);

        // Assert - second notification should still be processed
        verify(messagingTemplate, times(2)).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void markAsRead_ValidNotification_MarksAsRead() {
        // Arrange
        Notification notification = createNotification(userId, NotificationType.ORDER_CREATED);
        notification.setIsRead(false);

        when(notificationRepository.findById(notification.getId()))
                .thenReturn(Optional.of(notification));

        // Act
        notificationService.markAsRead(notification.getId(), userId);

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        assertThat(captor.getValue().getIsRead()).isTrue();
    }

    @Test
    void markAsRead_NotificationNotFound_ThrowsException() {
        // Arrange
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findById(notificationId))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> notificationService.markAsRead(notificationId, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Notification not found");
    }

    @Test
    void markAsRead_DifferentUser_ThrowsException() {
        // Arrange
        UUID differentUserId = UUID.randomUUID();
        Notification notification = createNotification(userId, NotificationType.ORDER_CREATED);

        when(notificationRepository.findById(notification.getId()))
                .thenReturn(Optional.of(notification));

        // Act & Assert
        assertThatThrownBy(() -> notificationService.markAsRead(notification.getId(), differentUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to user");
    }

    @Test
    void getUnreadNotifications_ReturnsOnlyUnreadNotifications() {
        // Arrange
        Notification unread1 = createNotification(userId, NotificationType.ORDER_CREATED);
        unread1.setIsRead(false);
        Notification unread2 = createNotification(userId, NotificationType.ORDER_PREPARING);
        unread2.setIsRead(false);

        when(notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(unread2, unread1));

        // Act
        List<NotificationDto> result = notificationService.getUnreadNotifications(userId);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo(NotificationType.ORDER_PREPARING);
        assertThat(result.get(1).getType()).isEqualTo(NotificationType.ORDER_CREATED);
    }

    @Test
    void getUnreadNotifications_NoUnreadNotifications_ReturnsEmptyList() {
        // Arrange
        when(notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId))
                .thenReturn(List.of());

        // Act
        List<NotificationDto> result = notificationService.getUnreadNotifications(userId);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getNotificationHistory_ReturnsPaginatedResults() {
        // Arrange
        Notification notif1 = createNotification(userId, NotificationType.ORDER_CREATED);
        Notification notif2 = createNotification(userId, NotificationType.ORDER_PREPARING);
        List<Notification> notifications = List.of(notif2, notif1);

        Pageable pageable = PageRequest.of(0, 10);
        Page<Notification> page = new PageImpl<>(notifications, pageable, notifications.size());

        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable))
                .thenReturn(page);

        // Act
        Page<NotificationDto> result = notificationService.getNotificationHistory(userId, pageable);

        // Assert
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void getNotificationHistory_EmptyHistory_ReturnsEmptyPage() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notification> emptyPage = new PageImpl<>(List.of(), pageable, 0);

        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable))
                .thenReturn(emptyPage);

        // Act
        Page<NotificationDto> result = notificationService.getNotificationHistory(userId, pageable);

        // Assert
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void createAndSendNotification_SerializationFailure_LogsError() throws Exception {
        // Arrange
        ObjectMapper faultyMapper = mock(ObjectMapper.class);
        NotificationService serviceWithFaultyMapper = new NotificationService(
                notificationRepository, messagingTemplate, userRegistry, faultyMapper);

        when(faultyMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("Serialization error"));

        // Act
        serviceWithFaultyMapper.createAndSendNotification(
                userId, NotificationType.ORDER_CREATED, "Test",
                orderId, null, null, Map.of("key", "value"));

        // Assert - should not throw, just log
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void createAndSendNotification_WithAllFields_SavesCorrectly() {
        // Arrange
        UUID storeId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        when(userRegistry.getUser(userId.toString())).thenReturn(null);

        // Act
        notificationService.createAndSendNotification(
                userId, NotificationType.ORDER_ASSIGNED, "Test message",
                orderId, storeId, courierId, Map.of("test", "data"));

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getOrderId()).isEqualTo(orderId);
        assertThat(saved.getStoreId()).isEqualTo(storeId);
        assertThat(saved.getCourierId()).isEqualTo(courierId);
        assertThat(saved.getMessage()).isEqualTo("Test message");
        assertThat(saved.getType()).isEqualTo(NotificationType.ORDER_ASSIGNED);
        assertThat(saved.getPayload()).isNotNull();
    }

    private Notification createNotification(UUID userId, NotificationType type) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setUserId(userId);
        notification.setType(type);
        notification.setOrderId(UUID.randomUUID());
        notification.setMessage("Test message");
        notification.setPayload("{}");
        notification.setIsRead(false);
        notification.setCreatedAt(Instant.now());
        return notification;
    }
}