package com.yads.courierservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AmqpConfig {

    // Dead Letter Exchange and Queue Configuration
    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange("dlx");
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue("q.dlq");
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with("#");
    }

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
        return QueueBuilder.durable("q.courier_service.assign_order")
                .withArgument("x-dead-letter-exchange", "dlx")
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
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

    // Exchange for courier-service events
    @Bean
    public TopicExchange courierEventsExchange() {
        return new TopicExchange("courier_events_exchange");
    }
}
