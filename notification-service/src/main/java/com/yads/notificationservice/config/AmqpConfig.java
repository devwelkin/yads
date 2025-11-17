package com.yads.notificationservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for notification service.
 *
 * Simple approach: ONE queue with multiple bindings for different event types.
 * This is cleaner and follows the pattern used in other services.
 */
@Configuration
public class AmqpConfig {

    /**
     * Reference to order-service's exchange.
     * Order-service publishes all order events to this exchange.
     */
    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange("order_events_exchange");
    }

    /**
     * Single queue for all order events.
     * Cleaner than having 5-6 separate queues.
     */
    @Bean
    public Queue orderEventsQueue() {
        return new Queue("q.notification_service.order_events");
    }

    /**
     * Bind queue to exchange for order.created events.
     */
    @Bean
    public Binding orderCreatedBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(orderEventsQueue)
                .to(orderEventsExchange)
                .with("order.created");
    }

    /**
     * Bind queue to exchange for order.preparing events.
     */
    @Bean
    public Binding orderPreparingBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(orderEventsQueue)
                .to(orderEventsExchange)
                .with("order.preparing");
    }

    /**
     * Bind queue to exchange for order.assigned events (NEW).
     */
    @Bean
    public Binding orderAssignedBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(orderEventsQueue)
                .to(orderEventsExchange)
                .with("order.assigned");
    }

    /**
     * Bind queue to exchange for order.on_the_way events.
     */
    @Bean
    public Binding orderOnTheWayBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(orderEventsQueue)
                .to(orderEventsExchange)
                .with("order.on_the_way");
    }

    /**
     * Bind queue to exchange for order.delivered events.
     */
    @Bean
    public Binding orderDeliveredBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(orderEventsQueue)
                .to(orderEventsExchange)
                .with("order.delivered");
    }

    /**
     * Bind queue to exchange for order.cancelled events.
     */
    @Bean
    public Binding orderCancelledBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(orderEventsQueue)
                .to(orderEventsExchange)
                .with("order.cancelled");
    }

    /**
     * JSON message converter for serializing/deserializing events.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}

