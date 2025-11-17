package com.yads.notificationservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration with STOMP protocol.
 *
 * Clients connect to /ws endpoint and can subscribe to /user/queue/notifications
 * to receive real-time push notifications.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable simple in-memory broker for user-specific destinations
        registry.enableSimpleBroker("/queue", "/topic");

        // Application destination prefix for messages from clients
        registry.setApplicationDestinationPrefixes("/app");

        // User destination prefix for user-specific messages
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket endpoint at /ws
        // Clients connect with: ws://localhost:8084/ws
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");  // Configure CORS as needed

        // Also support SockJS fallback for older browsers
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Register our JWT authentication interceptor
        // This intercepts STOMP CONNECT messages and validates JWT from headers
        registration.interceptors(webSocketAuthInterceptor);
    }
}

