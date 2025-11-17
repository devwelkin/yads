package com.yads.notificationservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.notificationservice.dto.NotificationDto;
import com.yads.notificationservice.model.Notification;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing notifications.
 *
 * Clean method signatures: event payload objects are passed directly,
 * and the service extracts the necessary IDs from them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry userRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Creates and sends a notification to a user.
     *
     * Clean signature: accepts event payload object directly.
     * Parses orderId, storeId, courierId from the payload automatically.
     *
     * @param userId Recipient user ID
     * @param type Notification type
     * @param message Human-readable message
     * @param eventPayload Event payload object (OrderResponse, OrderAssignmentContract, etc.)
     */
    @Transactional
    public void createAndSendNotification(UUID userId, NotificationType type, String message, Object eventPayload) {
        try {
            // Parse IDs from event payload
            UUID orderId = extractOrderId(eventPayload);
            UUID storeId = extractStoreId(eventPayload);
            UUID courierId = extractCourierId(eventPayload);

            // Serialize payload to JSON
            String payloadJson = objectMapper.writeValueAsString(eventPayload);

            // Create notification entity
            Notification notification = Notification.builder()
                    .userId(userId)
                    .type(type)
                    .orderId(orderId)
                    .storeId(storeId)
                    .courierId(courierId)
                    .message(message)
                    .payload(payloadJson)
                    .isRead(false)
                    .build();

            // Save to database
            Notification savedNotification = notificationRepository.save(notification);
            log.info("Notification created: id={}, userId={}, type={}",
                    savedNotification.getId(), userId, type);

            // Check if user is online (connected via WebSocket)
            boolean isOnline = userRegistry.getUser(userId.toString()) != null;

            if (isOnline) {
                // User is online - send immediately via WebSocket
                sendNotificationToUser(userId, savedNotification);

                // Mark as delivered
                savedNotification.setDeliveredAt(Instant.now());
                notificationRepository.save(savedNotification);

                log.info("Notification delivered immediately: id={}, userId={}",
                        savedNotification.getId(), userId);
            } else {
                // User is offline - notification will be sent when they connect
                log.info("User offline, notification queued: id={}, userId={}",
                        savedNotification.getId(), userId);
            }

        } catch (Exception e) {
            log.error("Failed to create and send notification: userId={}, type={}, error={}",
                    userId, type, e.getMessage(), e);
        }
    }

    /**
     * Sends pending (undelivered) notifications to a user.
     * Called when user connects to WebSocket.
     */
    @Transactional
    public void sendPendingNotifications(UUID userId) {
        log.info("Sending pending notifications to user: userId={}", userId);

        List<Notification> pendingNotifications =
                notificationRepository.findByUserIdAndDeliveredAtIsNullOrderByCreatedAtAsc(userId);

        if (pendingNotifications.isEmpty()) {
            log.info("No pending notifications for user: userId={}", userId);
            return;
        }

        log.info("Found {} pending notifications for user: userId={}",
                pendingNotifications.size(), userId);

        for (Notification notification : pendingNotifications) {
            try {
                sendNotificationToUser(userId, notification);

                // Mark as delivered
                notification.setDeliveredAt(Instant.now());
                notificationRepository.save(notification);

                log.info("Pending notification delivered: id={}, userId={}",
                        notification.getId(), userId);

            } catch (Exception e) {
                log.error("Failed to send pending notification: id={}, userId={}, error={}",
                        notification.getId(), userId, e.getMessage());
            }
        }
    }

    /**
     * Marks a notification as read.
     */
    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notificationId));

        if (!notification.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Notification does not belong to user");
        }

        notification.setIsRead(true);
        notificationRepository.save(notification);

        log.info("Notification marked as read: id={}, userId={}", notificationId, userId);
    }

    /**
     * Gets unread notifications for a user.
     */
    public List<NotificationDto> getUnreadNotifications(UUID userId) {
        List<Notification> notifications =
                notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId);

        return notifications.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Gets notification history for a user with pagination.
     */
    public Page<NotificationDto> getNotificationHistory(UUID userId, Pageable pageable) {
        Page<Notification> notifications =
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        return notifications.map(this::toDto);
    }

    /**
     * Sends a notification to a user via WebSocket.
     */
    private void sendNotificationToUser(UUID userId, Notification notification) {
        NotificationDto dto = toDto(notification);

        // Send to user-specific destination: /user/{userId}/queue/notifications
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/notifications",
                dto
        );

        log.debug("Notification sent via WebSocket: id={}, userId={}", notification.getId(), userId);
    }

    /**
     * Converts Notification entity to DTO.
     */
    private NotificationDto toDto(Notification notification) {
        return NotificationDto.builder()
                .id(notification.getId())
                .type(notification.getType())
                .orderId(notification.getOrderId())
                .storeId(notification.getStoreId())
                .courierId(notification.getCourierId())
                .message(notification.getMessage())
                .isRead(notification.getIsRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }

    /**
     * Extracts orderId from event payload using reflection.
     */
    private UUID extractOrderId(Object payload) {
        try {
            var method = payload.getClass().getMethod("getOrderId");
            return (UUID) method.invoke(payload);
        } catch (Exception e) {
            if (payload.getClass().getSimpleName().contains("OrderResponse")) {
                try {
                    var method = payload.getClass().getMethod("getId");
                    return (UUID) method.invoke(payload);
                } catch (Exception ex) {
                    log.error("Failed to extract orderId from payload: {}", payload.getClass().getName());
                    return null;
                }
            }
            log.error("Failed to extract orderId from payload: {}", payload.getClass().getName());
            return null;
        }
    }

    /**
     * Extracts storeId from event payload using reflection.
     */
    private UUID extractStoreId(Object payload) {
        try {
            var method = payload.getClass().getMethod("getStoreId");
            return (UUID) method.invoke(payload);
        } catch (Exception e) {
            log.debug("No storeId in payload: {}", payload.getClass().getName());
            return null;
        }
    }

    /**
     * Extracts courierId from event payload using reflection.
     */
    private UUID extractCourierId(Object payload) {
        try {
            var method = payload.getClass().getMethod("getCourierId");
            return (UUID) method.invoke(payload);
        } catch (Exception e) {
            log.debug("No courierId in payload: {}", payload.getClass().getName());
            return null;
        }
    }
}

