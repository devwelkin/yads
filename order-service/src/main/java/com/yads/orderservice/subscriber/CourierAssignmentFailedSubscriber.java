package com.yads.orderservice.subscriber;

import com.yads.common.contracts.CourierAssignmentFailedContract;
import com.yads.common.contracts.OrderCancelledContract;
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
 * Subscriber that handles courier assignment failure from courier-service.
 * Cancels the order when no courier can be assigned.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CourierAssignmentFailedSubscriber {

    private final OrderRepository orderRepository;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Handles courier assignment failure.
     * Cancels the order and notifies customer and store owner.
     */
    @RabbitListener(queues = "q.order_service.courier_assignment_failed")
    @Transactional
    public void handleCourierAssignmentFailed(CourierAssignmentFailedContract contract) {
        log.info("Received 'courier.assignment.failed' event. orderId={}, reason: {}",
                contract.getOrderId(), contract.getReason());

        Order order = orderRepository.findById(contract.getOrderId())
                .orElseThrow(() -> {
                    log.error("Order not found for courier assignment failure event. orderId={}",
                            contract.getOrderId());
                    return new ResourceNotFoundException("Order not found: " + contract.getOrderId());
                });

        if (order.getStatus() != OrderStatus.PREPARING) {
            log.warn("Order status is not PREPARING. Ignoring event (idempotency). orderId={}, status={}",
                    order.getId(), order.getStatus());
            return; // Idempotency: Already processed or in different state
        }

        // 1. Cancel the order (no courier available - cannot fulfill this order)
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        log.info("Order status set to CANCELLED due to courier assignment failure. orderId={}, reason: {}",
                order.getId(), contract.getReason());

        // 2. Notify customer and store about the cancellation and restore stock
        try {
            OrderCancelledContract cancellationContract = OrderCancelledContract.builder()
                    .orderId(contract.getOrderId())
                    .userId(contract.getUserId())
                    .storeId(order.getStoreId())
                    .oldStatus("PREPARING") // CRITICAL: Stock was reserved, must be restored
                    .items(order.getItems().stream()
                            .map(item -> com.yads.common.dto.BatchReserveItem.builder()
                                    .productId(item.getProductId())
                                    .quantity(item.getQuantity())
                                    .build())
                            .collect(java.util.stream.Collectors.toList()))
                    .build();

            rabbitTemplate.convertAndSend("order_events_exchange", "order.cancelled", cancellationContract);
            log.info("Sent 'order.cancelled' event for customer/store notification and stock restoration. " +
                    "orderId={}, reason: {}", order.getId(), contract.getReason());
        } catch (Exception e) {
            log.error("Failed to send cancellation event. orderId={}. error: {}", order.getId(), e.getMessage());
        }
    }
}

