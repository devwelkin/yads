package com.yads.orderservice.subscriber;

import com.yads.common.contracts.CourierAssignedContract;
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

import java.util.UUID;

/**
 * Subscriber that handles courier assignment events from courier-service.
 * Completes the async saga by updating the order's courierID.
 *
 * This replaces the synchronous REST PATCH endpoint that caused split-brain issues.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CourierAssignedSubscriber {

    private final OrderRepository orderRepository;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Handles courier.assigned events from courier-service.
     * Updates the order's courierID atomically.
     */
    @RabbitListener(queues = "q.order_service.courier_assigned")
    @Transactional
    public void handleCourierAssigned(CourierAssignedContract contract) {
        log.info("Received 'courier.assigned' event. orderId={}, courierId={}",
                contract.getOrderId(), contract.getCourierId());

        Order order = orderRepository.findById(contract.getOrderId())
                .orElseThrow(() -> {
                    log.error("Order not found for courier assignment event. orderId={}", contract.getOrderId());
                    return new ResourceNotFoundException("Order not found: " + contract.getOrderId());
                });

        // Idempotency check: If courier already assigned, log and skip
        if (order.getCourierId() != null) {
            if (order.getCourierId().equals(contract.getCourierId())) {
                log.info("Courier already assigned to order (idempotent replay). orderId={}, courierId={}",
                        contract.getOrderId(), contract.getCourierId());
                return; // Duplicate event - already processed
            } else {
                log.warn("Order already has a DIFFERENT courier assigned! orderId={}, existingCourierId={}, newCourierId={}",
                        contract.getOrderId(), order.getCourierId(), contract.getCourierId());
                // This should NEVER happen with proper locking in courier-service
                // Keep existing courier assignment
                return;
            }
        }

        // Validate order status
        if (order.getStatus() != OrderStatus.PREPARING) {
            log.warn("Order status is not PREPARING. Cannot assign courier. orderId={}, status={}",
                    contract.getOrderId(), order.getStatus());
            // Possible scenarios:
            // - Order was cancelled after courier assignment started
            // - Order somehow progressed to ON_THE_WAY (race condition - shouldn't happen)
            return;
        }

        // Assign courier
        order.setCourierId(contract.getCourierId());
        orderRepository.save(order);
        log.info("Courier assigned to order successfully. orderId={}, courierId={}",
                contract.getOrderId(), contract.getCourierId());

        // Publish order.assigned event for notification-service
        try {
            com.yads.common.contracts.OrderAssignedContract notificationContract =
                    com.yads.common.contracts.OrderAssignedContract.builder()
                            .orderId(order.getId())
                            .storeId(order.getStoreId())
                            .courierId(contract.getCourierId())
                            .userId(order.getUserId())
                            .pickupAddress(order.getPickupAddress())
                            .shippingAddress(order.getShippingAddress())
                            .build();

            rabbitTemplate.convertAndSend("order_events_exchange", "order.assigned", notificationContract);
            log.info("'order.assigned' event sent to notification-service. orderId={}, courierId={}",
                    order.getId(), contract.getCourierId());
        } catch (Exception e) {
            log.error("Failed to send 'order.assigned' notification event. orderId={}. error: {}",
                    order.getId(), e.getMessage());
            // Notification failure is not critical - courier is already assigned
        }
    }
}

