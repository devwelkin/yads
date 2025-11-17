package com.yads.notificationservice.repository;

import com.yads.notificationservice.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for managing notification persistence.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Find all unread notifications for a user, ordered by creation time (newest first).
     */
    List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(UUID userId);

    /**
     * Find all undelivered notifications for a user (offline user handling).
     * Ordered by creation time (oldest first) to deliver in chronological order.
     */
    List<Notification> findByUserIdAndDeliveredAtIsNullOrderByCreatedAtAsc(UUID userId);

    /**
     * Find notification history for a user with pagination.
     * Ordered by creation time (newest first).
     */
    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}

