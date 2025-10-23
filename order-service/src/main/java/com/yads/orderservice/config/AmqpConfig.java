// order-service/src/main/java/com/yads/orderservice/config/AmqpConfig.java
package com.yads.orderservice.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AmqpConfig {

    // TopicExchange allows flexible routing keys
    // like 'order.created' or 'order.cancelled'
    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange("order_events_exchange");
    }
}