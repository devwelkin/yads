package com.yads.courierservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AmqpConfig {

    // Reference to order-service's exchange
    // Order-service publishes events like "order.preparing" to this exchange
    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange("order_events_exchange");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // Queue for courier assignment
    // Courier-service will listen to this queue for orders that need a courier
    @Bean
    public Queue assignOrderQueue() {
        return new Queue("q.courier_service.assign_order");
    }

    // Bind the queue to order_events_exchange
    // Listen for "order.preparing" events (when store accepts order)
    @Bean
    public Binding assignOrderBinding(Queue assignOrderQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(assignOrderQueue)
                .to(orderEventsExchange)
                .with("order.preparing"); // Listen specifically for order.preparing events
    }
}


