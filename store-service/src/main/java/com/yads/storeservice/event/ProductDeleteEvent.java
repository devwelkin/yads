package com.yads.storeservice.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event published when a product is deleted.
 * This event is handled by @TransactionalEventListener to ensure
 * that external notifications (RabbitMQ) are only sent after
 * the database transaction successfully commits.
 */
@Getter
public class ProductDeleteEvent extends ApplicationEvent {
    private final UUID productId;

    public ProductDeleteEvent(Object source, UUID productId) {
        super(source);
        this.productId = productId;
    }
}

