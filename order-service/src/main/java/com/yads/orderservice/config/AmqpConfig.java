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
}