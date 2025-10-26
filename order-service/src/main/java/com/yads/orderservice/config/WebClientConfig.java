// order-service/src/main/java/com/yads/orderservice/config/WebClientConfig.java
package com.yads.orderservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced // This magical annotation tells WebClient to use Eureka
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public WebClient storeServiceWebClient(WebClient.Builder builder) {
        return builder.baseUrl("http://store-service").build();
    }
}