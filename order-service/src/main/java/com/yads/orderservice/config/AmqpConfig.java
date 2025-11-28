// order-service/src/main/java/com/yads/orderservice/config/AmqpConfig.java
package com.yads.orderservice.config;

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
    public static final String STORE_EXCHANGE = "store_events_exchange";
    public static final String COURIER_EXCHANGE = "courier_events_exchange";

    public static final String Q_PRODUCT_UPDATES = "q.order.product.updates";
    public static final String Q_STOCK_RESERVED = "q.order.stock.reserved";
    public static final String Q_STOCK_RESERVATION_FAILED = "q.order.stock.reservation.failed";
    public static final String Q_COURIER_ASSIGNED = "q.order.courier.assigned";
    public static final String Q_COURIER_ASSIGNMENT_FAILED = "q.order.courier.assignment.failed";

    public static final String ROUTING_KEY_PRODUCT_ALL = "product.#";
    public static final String ROUTING_KEY_STOCK_RESERVE_REQUEST = "order.stock_reservation.requested";
    public static final String ROUTING_KEY_STOCK_RESERVED = "order.stock_reserved";
    public static final String ROUTING_KEY_STOCK_RESERVATION_FAILED = "order.stock_reservation_failed";
    public static final String ROUTING_KEY_COURIER_ASSIGNED = "courier.assigned";
    public static final String ROUTING_KEY_COURIER_ASSIGNMENT_FAILED = "courier.assignment.failed";

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
    public TopicExchange storeEventsExchange() {
        return new TopicExchange(STORE_EXCHANGE);
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
    public Queue productUpdateQueue() {
        return createDurableQueue(Q_PRODUCT_UPDATES);
    }

    @Bean
    public Binding productUpdateBinding(Queue productUpdateQueue, TopicExchange storeEventsExchange) {
        return BindingBuilder.bind(productUpdateQueue).to(storeEventsExchange).with(ROUTING_KEY_PRODUCT_ALL);
    }

    // Producer: order-service doesn't define q.store.stock.reserve
    // That's store-service's inbox - they own it!
    // We only need to know the exchange and routing key to publish

    @Bean
    public Queue stockReservedQueue() {
        return createDurableQueue(Q_STOCK_RESERVED);
    }

    @Bean
    public Binding stockReservedBinding(Queue stockReservedQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(stockReservedQueue).to(orderEventsExchange).with(ROUTING_KEY_STOCK_RESERVED);
    }

    @Bean
    public Queue stockReservationFailedQueue() {
        return createDurableQueue(Q_STOCK_RESERVATION_FAILED);
    }

    @Bean
    public Binding stockReservationFailedBinding(Queue stockReservationFailedQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(stockReservationFailedQueue).to(orderEventsExchange)
                .with(ROUTING_KEY_STOCK_RESERVATION_FAILED);
    }

    @Bean
    public Queue courierAssignedQueue() {
        return createDurableQueue(Q_COURIER_ASSIGNED);
    }

    @Bean
    public Binding courierAssignedBinding(Queue courierAssignedQueue, TopicExchange courierEventsExchange) {
        return BindingBuilder.bind(courierAssignedQueue).to(courierEventsExchange).with(ROUTING_KEY_COURIER_ASSIGNED);
    }

    @Bean
    public Queue courierAssignmentFailedQueue() {
        return createDurableQueue(Q_COURIER_ASSIGNMENT_FAILED);
    }

    @Bean
    public Binding courierAssignmentFailedBinding(Queue courierAssignmentFailedQueue,
            TopicExchange courierEventsExchange) {
        return BindingBuilder.bind(courierAssignmentFailedQueue).to(courierEventsExchange)
                .with(ROUTING_KEY_COURIER_ASSIGNMENT_FAILED);
    }

    // DRY principle helper
    private Queue createDurableQueue(String queueName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", DLX_NAME)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }
}