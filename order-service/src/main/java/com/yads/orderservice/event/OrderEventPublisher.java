package com.yads.orderservice.event;

import com.yads.common.contracts.OrderAssignmentContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes domain events to RabbitMQ after database transaction commits.
 * Ensures messages are only sent if the transaction succeeds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderAccepted(OrderAcceptedEvent event) {
        log.info("Publishing order.preparing event after successful DB commit: orderId={}", event.getOrderId());

        OrderAssignmentContract contract = OrderAssignmentContract.builder()
                .orderId(event.getOrderId())
                .storeId(event.getStoreId())
                .pickupAddress(event.getPickupAddress())
                .shippingAddress(event.getShippingAddress())
                .build();

        try {
            rabbitTemplate.convertAndSend("order_events_exchange", "order.preparing", contract);
            log.info("'order.preparing' event sent to RabbitMQ. Order ID: {}", event.getOrderId());
        } catch (Exception e) {
            log.error("ERROR occurred while sending event to RabbitMQ. Order ID: {}. Error: {}",
                    event.getOrderId(), e.getMessage());
            // Consider implementing retry mechanism or dead letter queue
        }
    }
}

