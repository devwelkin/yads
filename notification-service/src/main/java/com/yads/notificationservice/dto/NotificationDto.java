package com.yads.notificationservice.dto;

import com.yads.notificationservice.model.NotificationType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for notification responses (REST and WebSocket).
 */
@Data
@Builder
public class NotificationDto {
    private UUID id;
    private NotificationType type;
    private UUID orderId;
    private UUID storeId;
    private UUID courierId;
    private String message;
    private Boolean isRead;
    private Instant createdAt;
}

