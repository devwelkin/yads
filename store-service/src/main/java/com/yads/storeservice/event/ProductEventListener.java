package com.yads.storeservice.event;

import com.yads.common.contracts.ProductEventDto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens to product events and publishes them to RabbitMQ.
 *
 * CRITICAL: This listener uses @TransactionalEventListener with AFTER_COMMIT phase.
 * This ensures that RabbitMQ events are ONLY sent AFTER the database transaction
 * successfully commits. If the transaction rolls back, NO events are sent.
 *
 * This prevents data inconsistency between the database and external services.
 *
 * Example scenario:
 * 1. Batch reserve 2 products: Product A (stock=10) and Product B (stock=5)
 * 2. Request: Reserve 2 of Product A and 10 of Product B
 * 3. Product A stock updated (10->8), saved to DB
 * 4. Product A update event is SCHEDULED (not sent yet)
 * 5. Product B fails (insufficient stock), exception thrown
 * 6. Transaction rolls back, Product A stock returns to 10
 * 7. AFTER_COMMIT listener is NOT called, so Product A event is NEVER sent
 * 8. Result: Consistent state - DB has stock=10, no event sent
 */
@Component
@RequiredArgsConstructor
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange storeEventsExchange;

    /**
     * Handles product update events AFTER the transaction commits.
     * If the transaction rolls back, this method is NOT called.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductUpdateEvent(ProductUpdateEvent event) {
        try {
            ProductEventDto eventDto = ProductEventDto.builder()
                    .productId(event.getProduct().getId())
                    .name(event.getProduct().getName())
                    .price(event.getProduct().getPrice())
                    .stock(event.getProduct().getStock())
                    .isAvailable(event.getProduct().getIsAvailable())
                    .storeId(event.getProduct().getCategory().getStore().getId())
                    .build();

            rabbitTemplate.convertAndSend(
                    storeEventsExchange.getName(),
                    event.getRoutingKey(),
                    eventDto
            );

            log.debug("Published product update event: productId={}, routingKey={}, stock={}",
                    event.getProduct().getId(), event.getRoutingKey(), event.getProduct().getStock());

        } catch (Exception e) {
            // Log error but don't throw - we don't want to fail the transaction
            // at this point (it's already committed)
            log.error("Failed to publish product update event: productId={}, routingKey={}",
                    event.getProduct().getId(), event.getRoutingKey(), e);
        }
    }

    /**
     * Handles product delete events AFTER the transaction commits.
     * If the transaction rolls back, this method is NOT called.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductDeleteEvent(ProductDeleteEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    storeEventsExchange.getName(),
                    "product.deleted",
                    event.getProductId()
            );

            log.debug("Published product delete event: productId={}", event.getProductId());

        } catch (Exception e) {
            // Log error but don't throw - we don't want to fail the transaction
            // at this point (it's already committed)
            log.error("Failed to publish product delete event: productId={}",
                    event.getProductId(), e);
        }
    }
}

