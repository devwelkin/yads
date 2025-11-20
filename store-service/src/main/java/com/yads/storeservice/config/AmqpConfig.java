package com.yads.storeservice.config;

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

    @Bean
    public TopicExchange storeEventsExchange() {
        // Exchange for store-related events (product updates)
        return new TopicExchange("store_events_exchange");
    }

    @Bean
    public TopicExchange orderEventsExchange() {
        // Exchange for order-related events (order cancellation, etc.)
        return new TopicExchange("order_events_exchange");
    }

    @Bean
    public Queue orderCancelledStockRestoreQueue() {
        // Queue for processing order cancellations and restoring stock
        // Durable: messages persist across broker restarts
        return QueueBuilder.durable("order_cancelled_stock_restore_queue")
                .withArgument("x-dead-letter-exchange", "dlx")
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    @Bean
    public Binding orderCancelledBinding(Queue orderCancelledStockRestoreQueue, TopicExchange orderEventsExchange) {
        // Bind queue to order.cancelled events
        return BindingBuilder.bind(orderCancelledStockRestoreQueue)
                .to(orderEventsExchange)
                .with("order.cancelled");
    }

    @Bean
    public Queue stockReservationRequestQueue() {
        // Queue for receiving stock reservation requests from order-service
        return QueueBuilder.durable("q.store_service.stock_reservation_request")
                .withArgument("x-dead-letter-exchange", "dlx")
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    @Bean
    public Binding stockReservationRequestBinding(Queue stockReservationRequestQueue,
            TopicExchange orderEventsExchange) {
        // Bind queue to order.stock_reservation.requested events
        return BindingBuilder.bind(stockReservationRequestQueue)
                .to(orderEventsExchange)
                .with("order.stock_reservation.requested");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}