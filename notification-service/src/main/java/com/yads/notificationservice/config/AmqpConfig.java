package com.yads.notificationservice.config;

import org.springframework.amqp.core.*;
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

    // Centralize these, ideally in a shared lib or constants class
    public static final String DLX_NAME = "dlx";
    public static final String DLQ_NAME = "q.dlq";
    public static final String DLQ_ROUTING_KEY = "dlq";

    public static final String ORDER_EXCHANGE = "order_events_exchange";
    public static final String Q_ORDER_EVENTS = "q.notification.order.events";

    public static final String ROUTING_KEY_ORDER_CREATED = "order.created";
    public static final String ROUTING_KEY_ORDER_PREPARING = "order.preparing";
    public static final String ROUTING_KEY_ORDER_ASSIGNED = "order.assigned";
    public static final String ROUTING_KEY_ORDER_ON_THE_WAY = "order.on_the_way";
    public static final String ROUTING_KEY_ORDER_DELIVERED = "order.delivered";
    public static final String ROUTING_KEY_ORDER_CANCELLED = "order.cancelled";

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX_NAME);
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DLQ_NAME);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with("#");
    }

    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange(ORDER_EXCHANGE);
    }

    @Bean
    public Queue orderEventsQueue() {
        return createDurableQueue(Q_ORDER_EVENTS);
    }

    @Bean
    public Binding orderCreatedBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsQueue).to(orderEventsExchange).with(ROUTING_KEY_ORDER_CREATED);
    }

    @Bean
    public Binding orderPreparingBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsQueue).to(orderEventsExchange).with(ROUTING_KEY_ORDER_PREPARING);
    }

    @Bean
    public Binding orderAssignedBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsQueue).to(orderEventsExchange).with(ROUTING_KEY_ORDER_ASSIGNED);
    }

    @Bean
    public Binding orderOnTheWayBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsQueue).to(orderEventsExchange).with(ROUTING_KEY_ORDER_ON_THE_WAY);
    }

    @Bean
    public Binding orderDeliveredBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsQueue).to(orderEventsExchange).with(ROUTING_KEY_ORDER_DELIVERED);
    }

    @Bean
    public Binding orderCancelledBinding(Queue orderEventsQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderEventsQueue).to(orderEventsExchange).with(ROUTING_KEY_ORDER_CANCELLED);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // DRY principle helper - protect against poison pills
    private Queue createDurableQueue(String queueName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", DLX_NAME)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }
}
