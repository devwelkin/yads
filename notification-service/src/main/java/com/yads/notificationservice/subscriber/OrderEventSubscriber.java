package com.yads.notificationservice.subscriber;

import com.yads.common.contracts.OrderAssignedContract;
import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.contracts.OrderStatusChangeContract;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to order events from RabbitMQ and creates notifications.
 *
 * Uses @RabbitHandler methods to handle different message types from the same queue.
 * This is cleaner than having multiple @RabbitListener methods or multiple queues.
 */
@Component
@RabbitListener(queues = "q.notification_service.order_events")
@RequiredArgsConstructor
@Slf4j
public class OrderEventSubscriber {

    private final NotificationService notificationService;

    /**
     * Handles order.created events.
     * Notifies: Customer (order placed successfully).
     */
    @RabbitHandler
    public void handleOrderCreated(OrderStatusChangeContract contract) {
        log.info("Received order.created event: orderId={}", contract.getOrderId());

        try {
            // Notify customer
            notificationService.createAndSendNotification(
                    contract.getUserId(),
                    NotificationType.ORDER_CREATED,
                    "Your order has been placed successfully and is awaiting store acceptance.",
                    contract
            );

            log.info("ORDER_CREATED notification sent to customer: orderId={}, userId={}",
                    contract.getOrderId(), contract.getUserId());

        } catch (Exception e) {
            log.error("Failed to handle order.created event: orderId={}, error={}",
                    contract.getOrderId(), e.getMessage(), e);
        }
    }

    /**
     * Handles order.preparing events.
     * Notifies: Customer (order accepted) + Store owner (order accepted).
     *
     * NOTE: Does NOT notify courier - courier hasn't been assigned yet!
     * Courier will be notified via order.assigned event.
     */
    @RabbitHandler
    public void handleOrderPreparing(OrderAssignmentContract contract) {
        log.info("Received order.preparing event: orderId={}, storeId={}",
                contract.getOrderId(), contract.getStoreId());

        try {
            // We need to get the userId - but OrderAssignmentContract doesn't have it
            // This is a limitation of the current contract
            // For now, we'll only notify based on storeId
            // TODO: Consider adding userId to OrderAssignmentContract or fetching order details

            log.info("ORDER_PREPARING event received but cannot notify customer without userId in contract");

            // We can still notify store owner if we have a way to determine owner userId
            // For now, we'll skip this notification or handle it differently
            // In a real system, you might:
            // 1. Add userId to the contract
            // 2. Make an HTTP call to order-service to fetch order details
            // 3. Store userId in a cache when order.created is received

        } catch (Exception e) {
            log.error("Failed to handle order.preparing event: orderId={}, error={}",
                    contract.getOrderId(), e.getMessage(), e);
        }
    }

    /**
     * Handles order.assigned events (NEW).
     * Notifies: Courier (you've been assigned to an order).
     */
    @RabbitHandler
    public void handleOrderAssigned(OrderAssignedContract contract) {
        log.info("Received order.assigned event: orderId={}, courierId={}",
                contract.getOrderId(), contract.getCourierId());

        try {
            // Notify courier
            notificationService.createAndSendNotification(
                    contract.getCourierId(),
                    NotificationType.ORDER_ASSIGNED,
                    "You've been assigned to deliver an order. Please pick it up from the store.",
                    contract
            );

            log.info("ORDER_ASSIGNED notification sent to courier: orderId={}, courierId={}",
                    contract.getOrderId(), contract.getCourierId());

            // Also notify customer that courier is assigned
            notificationService.createAndSendNotification(
                    contract.getUserId(),
                    NotificationType.ORDER_ASSIGNED,
                    "A courier has been assigned to your order and will pick it up soon.",
                    contract
            );

            log.info("ORDER_ASSIGNED notification sent to customer: orderId={}, userId={}",
                    contract.getOrderId(), contract.getUserId());

        } catch (Exception e) {
            log.error("Failed to handle order.assigned event: orderId={}, error={}",
                    contract.getOrderId(), e.getMessage(), e);
        }
    }

    /**
     * Handles order.on_the_way events.
     * Notifies: Customer (courier picked up) + Store owner (order en route).
     */
    @RabbitHandler
    public void handleOrderOnTheWay(OrderStatusChangeContract contract) {
        log.info("Received order.on_the_way event: orderId={}", contract.getOrderId());

        try {
            // Notify customer
            notificationService.createAndSendNotification(
                    contract.getUserId(),
                    NotificationType.ORDER_ON_THE_WAY,
                    "Your order is on the way! The courier has picked it up and is heading to your address.",
                    contract
            );

            log.info("ORDER_ON_THE_WAY notification sent to customer: orderId={}, userId={}",
                    contract.getOrderId(), contract.getUserId());

            // Store owner notification would require store owner userId
            // Similar issue as order.preparing

        } catch (Exception e) {
            log.error("Failed to handle order.on_the_way event: orderId={}, error={}",
                    contract.getOrderId(), e.getMessage(), e);
        }
    }

    /**
     * Handles order.delivered events.
     * Notifies: Customer (order delivered) + Store owner (order completed) + Courier (delivery confirmed).
     */
    @RabbitHandler
    public void handleOrderDelivered(OrderStatusChangeContract contract) {
        log.info("Received order.delivered event: orderId={}", contract.getOrderId());

        try {
            // Notify customer
            notificationService.createAndSendNotification(
                    contract.getUserId(),
                    NotificationType.ORDER_DELIVERED,
                    "Your order has been delivered successfully. Enjoy!",
                    contract
            );

            log.info("ORDER_DELIVERED notification sent to customer: orderId={}, userId={}",
                    contract.getOrderId(), contract.getUserId());

            // Notify courier
            if (contract.getCourierId() != null) {
                notificationService.createAndSendNotification(
                        contract.getCourierId(),
                        NotificationType.ORDER_DELIVERED,
                        "Order delivery confirmed. Great job!",
                        contract
                );

                log.info("ORDER_DELIVERED notification sent to courier: orderId={}, courierId={}",
                        contract.getOrderId(), contract.getCourierId());
            }

            // Store owner notification would require store owner userId

        } catch (Exception e) {
            log.error("Failed to handle order.delivered event: orderId={}, error={}",
                    contract.getOrderId(), e.getMessage(), e);
        }
    }

    /**
     * Handles order.cancelled events.
     * Notifies: Customer + Store owner + Courier (if assigned).
     */
    @RabbitHandler
    public void handleOrderCancelled(OrderCancelledContract contract) {
        log.info("Received order.cancelled event: orderId={}, oldStatus={}",
                contract.getOrderId(), contract.getOldStatus());

        try {
            // Note: OrderCancelledContract doesn't include userId or courierId
            // This is a limitation we need to work around
            // In production, you'd want to either:
            // 1. Enhance the contract to include these fields
            // 2. Cache order details when order.created is received
            // 3. Make HTTP call to order-service (not recommended for events)

            log.info("ORDER_CANCELLED event received but missing userId/courierId in contract");

            // For now, we can only log this
            // In a real implementation, you'd need the userIds to send notifications

        } catch (Exception e) {
            log.error("Failed to handle order.cancelled event: orderId={}, error={}",
                    contract.getOrderId(), e.getMessage(), e);
        }
    }
}

