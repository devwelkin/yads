package com.yads.storeservice.event;

import com.yads.storeservice.model.Product;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Event published when a product is updated.
 * This event is handled by @TransactionalEventListener to ensure
 * that external notifications (RabbitMQ) are only sent after
 * the database transaction successfully commits.
 */
@Getter
public class ProductUpdateEvent extends ApplicationEvent {
    private final Product product;
    private final String routingKey;

    public ProductUpdateEvent(Object source, Product product, String routingKey) {
        super(source);
        this.product = product;
        this.routingKey = routingKey;
    }
}

