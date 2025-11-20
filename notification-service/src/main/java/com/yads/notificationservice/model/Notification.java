package com.yads.notificationservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Notification entity representing a notification sent to a user.
 * Stores notification history and delivery status for offline user handling.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_user_read", columnList = "user_id,is_read"),
        @Index(name = "idx_user_delivered", columnList = "user_id,delivered_at")
})
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId; // Recipient user

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "store_id")
    private UUID storeId;

    @Column(name = "courier_id")
    private UUID courierId;

    @Column(nullable = false, length = 500)
    private String message; // Human-readable message

    @Column(columnDefinition = "TEXT")
    private String payload; // JSON with full event data

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "delivered_at")
    private Instant deliveredAt; // When WebSocket delivered (null = pending)

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
