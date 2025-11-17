package com.yads.notificationservice.model;

/**
 * Types of notifications that can be sent to users.
 */
public enum NotificationType {
    ORDER_CREATED,      // Order placed successfully
    ORDER_PREPARING,    // Store accepted order
    ORDER_ASSIGNED,     // Courier assigned to order
    ORDER_ON_THE_WAY,   // Courier picked up order
    ORDER_DELIVERED,    // Order delivered successfully
    ORDER_CANCELLED     // Order cancelled
}

