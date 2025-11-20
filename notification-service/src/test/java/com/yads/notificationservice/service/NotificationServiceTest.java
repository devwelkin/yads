package com.yads.notificationservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
}