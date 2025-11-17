// order-service/src/main/java/com/yads/orderservice/config/AmqpConfig.java
package com.yads.orderservice.config;

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

    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange("order_events_exchange");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public TopicExchange storeEventsExchange() {
        return new TopicExchange("store_events_exchange");
    }

    @Bean
    public Queue productUpdateQueue() {
        return new Queue("q.order_service.product_updates");
    }

    @Bean
    public Binding productUpdateBinding(Queue productUpdateQueue, TopicExchange storeEventsExchange) {
        return BindingBuilder
                .bind(productUpdateQueue)
                .to(storeEventsExchange)
                .with("product.#");
    }

    // Stock Reservation Saga - Outbound (Request)
    @Bean
    public Queue stockReservationRequestQueue() {
        return new Queue("q.store_service.stock_reservation_request");
    }

    @Bean
    public Binding stockReservationRequestBinding(Queue stockReservationRequestQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(stockReservationRequestQueue)
                .to(orderEventsExchange)
                .with("order.stock_reservation.requested");
    }

    // Stock Reservation Saga - Inbound (Success Response)
    @Bean
    public Queue stockReservedQueue() {
        return new Queue("q.order_service.stock_reserved");
    }

    @Bean
    public Binding stockReservedBinding(Queue stockReservedQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(stockReservedQueue)
                .to(orderEventsExchange)
                .with("order.stock_reserved");
    }

    // Stock Reservation Saga - Inbound (Failure Response)
    @Bean
    public Queue stockReservationFailedQueue() {
        return new Queue("q.order_service.stock_reservation_failed");
    }

    @Bean
    public Binding stockReservationFailedBinding(Queue stockReservationFailedQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder
                .bind(stockReservationFailedQueue)
                .to(orderEventsExchange)
                .with("order.stock_reservation_failed");
    }
}