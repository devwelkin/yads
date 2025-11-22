package com.yads.storeservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AmqpConfig {

    // centralize these, ideally in a shared lib or constants class
    public static final String DLX_NAME = "dlx";
    public static final String DLQ_NAME = "q.dlq";
    public static final String DLQ_ROUTING_KEY = "dlq";
    public static final String STORE_EXCHANGE = "store_events_exchange";
    public static final String ORDER_EXCHANGE = "order_events_exchange";

    public static final String Q_STOCK_RESTORE = "q.store.stock.restore";
    public static final String Q_STOCK_RESERVE = "q.store.stock.reserve";

    public static final String ROUTING_KEY_ORDER_CANCELLED = "order.cancelled";
    public static final String ROUTING_KEY_STOCK_RESERVE = "order.stock_reservation.requested";

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
    public TopicExchange storeEventsExchange() {
        return new TopicExchange(STORE_EXCHANGE);
    }

    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange(ORDER_EXCHANGE);
    }

    @Bean
    public Queue orderCancelledStockRestoreQueue() {
        return createDurableQueue(Q_STOCK_RESTORE);
    }

    @Bean
    public Binding orderCancelledBinding(Queue orderCancelledStockRestoreQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderCancelledStockRestoreQueue)
                .to(orderEventsExchange)
                .with(ROUTING_KEY_ORDER_CANCELLED);
    }

    @Bean
    public Queue stockReservationRequestQueue() {
        return createDurableQueue(Q_STOCK_RESERVE);
    }

    @Bean
    public Binding stockReservationRequestBinding(Queue stockReservationRequestQueue,
            TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(stockReservationRequestQueue)
                .to(orderEventsExchange)
                .with(ROUTING_KEY_STOCK_RESERVE);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // DRY principle helper
    private Queue createDurableQueue(String queueName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", DLX_NAME)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }
}