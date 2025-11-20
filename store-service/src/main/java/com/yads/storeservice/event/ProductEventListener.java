package com.yads.storeservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.common.contracts.ProductEventDto;
import com.yads.storeservice.model.OutboxEvent;
import com.yads.storeservice.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Listens to product events and saves them to the Outbox table.
 *
 * REFACTORED: Now uses the Outbox Pattern to ensure data consistency.
 * Instead of publishing directly to RabbitMQ (which could fail after DB
 * commit),
 * we save the event to the 'outbox_events' table within the SAME transaction.
 *
 * The OutboxPublisher job will later pick up these events and send them to
 * RabbitMQ.
 */
@Component
@RequiredArgsConstructor
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Handles product update events synchronously within the transaction.
     */
    @EventListener
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

            saveOutboxEvent(
                    event.getProduct().getId().toString(),
                    event.getRoutingKey(),
                    eventDto);

            log.info("Saved product update event to Outbox: productId={}, routingKey={}",
                    event.getProduct().getId(), event.getRoutingKey());

        } catch (Exception e) {
            log.error("Failed to save product update event to Outbox: productId={}",
                    event.getProduct().getId(), e);
            // Throwing exception here will rollback the transaction, which is what we want!
            throw new RuntimeException("Failed to save outbox event", e);
        }
    }

    /**
     * Handles product delete events synchronously within the transaction.
     */
    @EventListener
    public void handleProductDeleteEvent(ProductDeleteEvent event) {
        try {
            saveOutboxEvent(
                    event.getProductId().toString(),
                    "product.deleted",
                    event.getProductId() // Payload is just the UUID
            );

            log.info("Saved product delete event to Outbox: productId={}", event.getProductId());

        } catch (Exception e) {
            log.error("Failed to save product delete event to Outbox: productId={}",
                    event.getProductId(), e);
            throw new RuntimeException("Failed to save outbox event", e);
        }
    }

    private void saveOutboxEvent(String aggregateId, String type, Object payloadObj) {
        try {
            String payload = objectMapper.writeValueAsString(payloadObj);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("PRODUCT")
                    .aggregateId(aggregateId)
                    .type(type)
                    .payload(payload)
                    .createdAt(LocalDateTime.now())
                    .processed(false)
                    .build();
            outboxRepository.save(outboxEvent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize/save outbox event", e);
        }
    }
}
