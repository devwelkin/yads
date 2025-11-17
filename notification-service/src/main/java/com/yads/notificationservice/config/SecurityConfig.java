package com.yads.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for notification service.
 *
 * - REST endpoints require OAuth2 JWT authentication (Keycloak)
 * - WebSocket endpoint (/ws) is publicly accessible but requires JWT in STOMP headers
 *   (authentication handled by WebSocketAuthInterceptor)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())  // Disable CSRF for REST API and WebSocket
            .authorizeHttpRequests(auth -> auth
                // WebSocket endpoint is publicly accessible (JWT validated in interceptor)
                .requestMatchers("/ws/**").permitAll()

                // All REST API endpoints require authentication
                .requestMatchers("/api/v1/**").authenticated()

                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})  // Use default JWT configuration from application.yml
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            );

        return http.build();
    }
}

