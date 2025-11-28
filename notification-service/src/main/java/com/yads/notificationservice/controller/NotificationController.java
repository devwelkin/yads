package com.yads.notificationservice.controller;

import com.yads.notificationservice.dto.NotificationDto;
import com.yads.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Controller for notification REST API and WebSocket endpoints.
 *
 * REST endpoints require JWT authentication via Authorization header.
 * WebSocket subscriptions require JWT via STOMP CONNECT header.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * WebSocket subscription endpoint.
     * Called when client subscribes to /app/notifications
     * Triggers sending of pending notifications to the user.
     *
     * Client usage:
     * stompClient.subscribe('/user/queue/notifications', onMessage);
     * stompClient.send('/app/notifications', {}, '');
     */
    @MessageMapping("/notifications")
    public void subscribeToNotifications(Principal principal) {
        if (principal == null) {
            log.warn("WebSocket subscription attempt without authentication");
            return;
        }

        String userIdStr = principal.getName();
        UUID userId = UUID.fromString(userIdStr);

        log.info("User subscribed to notifications via WebSocket: userId={}", userId);

        // Send any pending notifications to the newly connected user
        notificationService.sendPendingNotifications(userId);
    }

    /**
     * REST endpoint: Get unread notifications for the authenticated user.
     *
     * GET /api/v1/notifications/unread
     * Authorization: Bearer {jwt}
     */
    @GetMapping("/api/v1/notifications/unread")
    @ResponseBody
    public ResponseEntity<List<NotificationDto>> getUnreadNotifications(Authentication authentication) {
        UUID userId = extractUserId(authentication);

        log.info("Getting unread notifications: userId={}", userId);

        List<NotificationDto> notifications = notificationService.getUnreadNotifications(userId);

        return ResponseEntity.ok(notifications);
    }

    /**
     * REST endpoint: Get notification history with pagination.
     *
     * GET /api/v1/notifications/history?page=0&size=20
     * Authorization: Bearer {jwt}
     */
    @GetMapping("/api/v1/notifications/history")
    @ResponseBody
    public ResponseEntity<Page<NotificationDto>> getNotificationHistory(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID userId = extractUserId(authentication);

        log.info("Getting notification history: userId={}, page={}, size={}", userId, page, size);

        // Validate pagination parameters
        if (page < 0) {
            throw new IllegalArgumentException("Page number must not be less than zero");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Page size must not be less than one");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<NotificationDto> notifications = notificationService.getNotificationHistory(userId, pageable);

        return ResponseEntity.ok(notifications);
    }

    /**
     * REST endpoint: Mark a notification as read.
     *
     * PUT /api/v1/notifications/{id}/read
     * Authorization: Bearer {jwt}
     */
    @PutMapping("/api/v1/notifications/{id}/read")
    @ResponseBody
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID id,
            Authentication authentication) {

        UUID userId = extractUserId(authentication);

        log.info("Marking notification as read: notificationId={}, userId={}", id, userId);

        try {
            notificationService.markAsRead(id, userId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.warn("Failed to mark notification as read: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Extracts userId from JWT authentication.
     */
    private UUID extractUserId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        throw new IllegalStateException("Invalid authentication principal");
    }
}
