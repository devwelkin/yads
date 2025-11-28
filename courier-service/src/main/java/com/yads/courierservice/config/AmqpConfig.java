package com.yads.courierservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AmqpConfig {

    // Centralize these, ideally in a shared lib or constants class
    public static final String DLX_NAME = "dlx";
    public static final String DLQ_NAME = "q.dlq";
    public static final String DLQ_ROUTING_KEY = "dlq";

    public static final String ORDER_EXCHANGE = "order_events_exchange";
    public static final String COURIER_EXCHANGE = "courier_events_exchange";

    public static final String Q_ASSIGN_ORDER = "q.courier.assign.order";

    public static final String ROUTING_KEY_ORDER_PREPARING = "order.preparing";

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
    public TopicExchange courierEventsExchange() {
        return new TopicExchange(COURIER_EXCHANGE);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public Queue assignOrderQueue() {
        return createDurableQueue(Q_ASSIGN_ORDER);
    }

    @Bean
    public Binding assignOrderBinding(Queue assignOrderQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(assignOrderQueue).to(orderEventsExchange).with(ROUTING_KEY_ORDER_PREPARING);
    }

    // DRY principle helper
    private Queue createDurableQueue(String queueName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", DLX_NAME)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }
}
