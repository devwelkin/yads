package com.yads.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .authorizeExchange(exchange -> exchange
                        // --- PUBLIC ENDPOINTS (GET requests) ---
                        // Allow access to store-service's public GET endpoints through gateway
                        .pathMatchers(HttpMethod.GET, "/store-service/api/v1/stores/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/store-service/api/v1/categories/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/store-service/api/v1/products/**").permitAll()

                        // --- PROTECTED ENDPOINTS ---
                        // Keep entire user-service protected
                        .pathMatchers("/user-service/**").authenticated()
                        // Keep rest of store-service (POST, PATCH etc.) protected
                        .pathMatchers("/store-service/**").authenticated()
                        .pathMatchers("/order-service/**").authenticated()
                        .pathMatchers("/courier-service/**").authenticated()

                        // Everything else (e.g. if /order-service/** comes)
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
