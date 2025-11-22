package com.yads.orderservice.subscriber;

import com.yads.common.contracts.StockReservationFailedContract;
import com.yads.common.contracts.StockReservedContract;
import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.orderservice.config.AmqpConfig;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.repository.OrderRepository;
import com.yads.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Subscriber that handles stock reservation saga responses from store-service.
 * This completes the async order acceptance flow initiated in
 * OrderServiceImpl.acceptOrder().
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockReplySubscriber {

    private final OrderRepository orderRepository;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Handles successful stock reservation.
     * Updates order status to PREPARING, saves pickup address, and triggers courier
     * assignment saga.
     */
    @RabbitListener(queues = AmqpConfig.Q_STOCK_RESERVED)
    @Transactional
    public void handleStockReserved(StockReservedContract contract) {
        log.info("Received 'order.stock_reserved' event. orderId={}", contract.getOrderId());
        Order order = findOrderOrThrow(contract.getOrderId());

        if (order.getStatus() != OrderStatus.RESERVING_STOCK) {
            log.warn("Order status is not RESERVING_STOCK. Ignoring event (idempotency). orderId={}, status={}",
                    order.getId(), order.getStatus());
            return; // Idempotency: Already processed or cancelled
        }

        // 1. Snapshot pickup address from store-service
        order.setPickupAddress(contract.getPickupAddress());

        // 2. Update order status to PREPARING
        order.setStatus(OrderStatus.PREPARING);
        orderRepository.save(order);
        log.info("Order status updated to PREPARING with pickup address. orderId={}", order.getId());

        // 2. Trigger courier assignment saga (same as old OrderEventPublisher behavior)
        OrderAssignmentContract courierContract = OrderAssignmentContract.builder()
                .orderId(contract.getOrderId())
                .storeId(contract.getStoreId())
                .userId(contract.getUserId())
                .pickupAddress(contract.getPickupAddress())
                .shippingAddress(contract.getShippingAddress())
                .build();

        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.preparing", courierContract);
            log.info("'order.preparing' (for courier assignment) event sent. orderId={}", order.getId());
        } catch (Exception e) {
            log.error("Failed to send 'order.preparing' event. orderId={}. error: {}", order.getId(), e.getMessage());
            // Courier assignment failure should be handled by retry mechanism or dead
            // letter queue
        }
    }

    /**
     * Handles failed stock reservation.
     * Cancels the order and notifies customer about the cancellation.
     */
    @RabbitListener(queues = AmqpConfig.Q_STOCK_RESERVATION_FAILED)
    @Transactional
    public void handleStockReservationFailed(StockReservationFailedContract contract) {
        log.info("Received 'order.stock_reservation_failed' event. orderId={}, reason: {}",
                contract.getOrderId(), contract.getReason());
        Order order = findOrderOrThrow(contract.getOrderId());

        if (order.getStatus() != OrderStatus.RESERVING_STOCK) {
            log.warn("Order status is not RESERVING_STOCK. Ignoring event (idempotency). orderId={}, status={}",
                    order.getId(), order.getStatus());
            return; // Idempotency: Already processed
        }

        // 1. Cancel the order (stock reservation failed - cannot fulfill this order)
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        log.info("Order status set to CANCELLED due to stock reservation failure. orderId={}, reason: {}",
                order.getId(), contract.getReason());

        // 2. Notify customer about the cancellation
        try {
            OrderCancelledContract cancellationContract = OrderCancelledContract.builder()
                    .orderId(contract.getOrderId())
                    .userId(contract.getUserId())
                    .storeId(order.getStoreId())
                    .oldStatus("RESERVING_STOCK") // Stock was never reserved, so no restoration needed
                    .items(List.of()) // Empty list - no stock to restore
                    .build();

            rabbitTemplate.convertAndSend("order_events_exchange", "order.cancelled", cancellationContract);
            log.info("Sent 'order.cancelled' event for customer notification. orderId={}, reason: {}",
                    order.getId(), contract.getReason());
        } catch (Exception e) {
            log.error("Failed to send failure notification event. orderId={}. error: {}", order.getId(),
                    e.getMessage());
        }
    }

    private Order findOrderOrThrow(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Order not found for stock reply event. orderId={}", orderId);
                    // This is a serious issue - the event refers to a non-existent order
                    // In production, this should trigger an alert
                    return new ResourceNotFoundException("Order not found: " + orderId);
                });
    }
}
