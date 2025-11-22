package com.yads.notificationservice.subscriber;

import com.yads.common.contracts.OrderAssignedContract;
import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.contracts.OrderStatusChangeContract;
import com.yads.notificationservice.config.AmqpConfig;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Subscribes to order events from RabbitMQ and creates notifications.
 *
 * Uses @RabbitHandler methods to handle different message types from the same
 * queue.
 * This is cleaner than having multiple @RabbitListener methods or multiple
 * queues.
 */
@Component
@RabbitListener(queues = AmqpConfig.Q_ORDER_EVENTS)
@RequiredArgsConstructor
@Slf4j
public class OrderEventSubscriber {

        private final NotificationService notificationService;

        /**
         * Consolidated handler for OrderStatusChangeContract-based events.
         * Handles: order.created, order.on_the_way, order.delivered
         *
         * Uses routing key to dispatch to appropriate logic, avoiding Spring AMQP's
         * "Ambiguous methods" error when multiple @RabbitHandler methods accept same
         * type.
         */
        @RabbitHandler
        public void handleOrderStatusChange(OrderStatusChangeContract contract,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
                log.info("Received OrderStatusChange event: routingKey={}, orderId={}", routingKey,
                                contract.getOrderId());

                switch (routingKey) {
                        case "order.created" -> processOrderCreated(contract);
                        case "order.on_the_way" -> processOrderOnTheWay(contract);
                        case "order.delivered" -> processOrderDelivered(contract);
                        default -> log.warn("Unknown routing key for OrderStatusChangeContract: {}", routingKey);
                }
        }

        private void processOrderCreated(OrderStatusChangeContract contract) {
                log.info("Processing order.created: orderId={}", contract.getOrderId());

                try {
                        notificationService.createAndSendNotification(
                                        contract.getUserId(),
                                        NotificationType.ORDER_CREATED,
                                        "Your order has been placed successfully and is awaiting store acceptance.",
                                        contract.getOrderId(),
                                        contract.getStoreId(),
                                        contract.getCourierId(),
                                        contract);

                        log.info("ORDER_CREATED notification sent to customer: orderId={}, userId={}",
                                        contract.getOrderId(), contract.getUserId());

                } catch (Exception e) {
                        log.error("Failed to process order.created event: orderId={}, error={}",
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
                log.info("Received order.preparing event: orderId={}, storeId={}, userId={}",
                                contract.getOrderId(), contract.getStoreId(), contract.getUserId());

                try {
                        // Notify customer - now we have userId!
                        notificationService.createAndSendNotification(
                                        contract.getUserId(),
                                        NotificationType.ORDER_PREPARING,
                                        "Great news! The store has accepted your order and is preparing it.",
                                        contract.getOrderId(),
                                        contract.getStoreId(),
                                        null, // no courier yet
                                        contract);

                        log.info("ORDER_PREPARING notification sent to customer: orderId={}, userId={}",
                                        contract.getOrderId(), contract.getUserId());

                        // Store owner notification would require store owner userId
                        // Could be added in future if needed

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
                        // Notify courier - type-safe
                        notificationService.createAndSendNotification(
                                        contract.getCourierId(),
                                        NotificationType.ORDER_ASSIGNED,
                                        "You've been assigned to deliver an order. Please pick it up from the store.",
                                        contract.getOrderId(),
                                        contract.getStoreId(),
                                        contract.getCourierId(),
                                        contract);

                        log.info("ORDER_ASSIGNED notification sent to courier: orderId={}, courierId={}",
                                        contract.getOrderId(), contract.getCourierId());

                        // Also notify customer that courier is assigned
                        notificationService.createAndSendNotification(
                                        contract.getUserId(),
                                        NotificationType.ORDER_ASSIGNED,
                                        "A courier has been assigned to your order and will pick it up soon.",
                                        contract.getOrderId(),
                                        contract.getStoreId(),
                                        contract.getCourierId(),
                                        contract);

                        log.info("ORDER_ASSIGNED notification sent to customer: orderId={}, userId={}",
                                        contract.getOrderId(), contract.getUserId());

                } catch (Exception e) {
                        log.error("Failed to handle order.assigned event: orderId={}, error={}",
                                        contract.getOrderId(), e.getMessage(), e);
                }
        }

        private void processOrderOnTheWay(OrderStatusChangeContract contract) {
                log.info("Processing order.on_the_way: orderId={}", contract.getOrderId());

                try {
                        notificationService.createAndSendNotification(
                                        contract.getUserId(),
                                        NotificationType.ORDER_ON_THE_WAY,
                                        "Your order is on the way! The courier has picked it up and is heading to your address.",
                                        contract.getOrderId(),
                                        contract.getStoreId(),
                                        contract.getCourierId(),
                                        contract);

                        log.info("ORDER_ON_THE_WAY notification sent to customer: orderId={}, userId={}",
                                        contract.getOrderId(), contract.getUserId());

                } catch (Exception e) {
                        log.error("Failed to process order.on_the_way event: orderId={}, error={}",
                                        contract.getOrderId(), e.getMessage(), e);
                }
        }

        private void processOrderDelivered(OrderStatusChangeContract contract) {
                log.info("Processing order.delivered: orderId={}", contract.getOrderId());

                try {
                        // Notify customer
                        notificationService.createAndSendNotification(
                                        contract.getUserId(),
                                        NotificationType.ORDER_DELIVERED,
                                        "Your order has been delivered successfully. Enjoy!",
                                        contract.getOrderId(),
                                        contract.getStoreId(),
                                        contract.getCourierId(),
                                        contract);

                        log.info("ORDER_DELIVERED notification sent to customer: orderId={}, userId={}",
                                        contract.getOrderId(), contract.getUserId());

                        // Notify courier
                        if (contract.getCourierId() != null) {
                                notificationService.createAndSendNotification(
                                                contract.getCourierId(),
                                                NotificationType.ORDER_DELIVERED,
                                                "Order delivery confirmed. Great job!",
                                                contract.getOrderId(),
                                                contract.getStoreId(),
                                                contract.getCourierId(),
                                                contract);

                                log.info("ORDER_DELIVERED notification sent to courier: orderId={}, courierId={}",
                                                contract.getOrderId(), contract.getCourierId());
                        }

                } catch (Exception e) {
                        log.error("Failed to process order.delivered event: orderId={}, error={}",
                                        contract.getOrderId(), e.getMessage(), e);
                }
        }

        /**
         * Handles order.cancelled events.
         * Notifies: Customer + Store owner + Courier (if assigned).
         */
        @RabbitHandler
        public void handleOrderCancelled(OrderCancelledContract contract) {
                log.info("Received order.cancelled event: orderId={}, oldStatus={}, userId={}, courierId={}",
                                contract.getOrderId(), contract.getOldStatus(), contract.getUserId(),
                                contract.getCourierId());

                try {
                        // Notify customer - type-safe, now we have userId!
                        notificationService.createAndSendNotification(
                                        contract.getUserId(),
                                        NotificationType.ORDER_CANCELLED,
                                        "Your order has been cancelled.",
                                        contract.getOrderId(),
                                        contract.getStoreId(),
                                        contract.getCourierId(),
                                        contract);

                        log.info("ORDER_CANCELLED notification sent to customer: orderId={}, userId={}",
                                        contract.getOrderId(), contract.getUserId());

                        // Notify courier if they were assigned
                        if (contract.getCourierId() != null) {
                                notificationService.createAndSendNotification(
                                                contract.getCourierId(),
                                                NotificationType.ORDER_CANCELLED,
                                                "The order you were assigned to has been cancelled.",
                                                contract.getOrderId(),
                                                contract.getStoreId(),
                                                contract.getCourierId(),
                                                contract);

                                log.info("ORDER_CANCELLED notification sent to courier: orderId={}, courierId={}",
                                                contract.getOrderId(), contract.getCourierId());
                        }

                        // Store owner notification would require store owner userId
                        // Could be added in future if needed

                } catch (Exception e) {
                        log.error("Failed to handle order.cancelled event: orderId={}, error={}",
                                        contract.getOrderId(), e.getMessage(), e);
                }
        }
}
