package com.yads.notificationservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * WebSocket authentication interceptor that validates JWT tokens from STOMP headers.
 *
 * SECURE APPROACH: Extracts JWT from Authorization header in STOMP CONNECT frame,
 * NOT from query parameters (which get logged everywhere).
 *
 * Client usage:
 * ```javascript
 * const socket = new SockJS('http://localhost:8084/ws');
 * const stompClient = Stomp.over(socket);
 * stompClient.connect(
 *   { 'Authorization': 'Bearer ' + jwtToken },  // JWT in header
 *   onConnect,
 *   onError
 * );
 * ```
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            // Extract Authorization header from STOMP CONNECT frame
            List<String> authorizationHeaders = accessor.getNativeHeader("Authorization");

            if (authorizationHeaders != null && !authorizationHeaders.isEmpty()) {
                String authHeader = authorizationHeaders.get(0);

                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);

                    try {
                        // Validate JWT token using Keycloak public key
                        Jwt jwt = jwtDecoder.decode(token);
                        String userId = jwt.getSubject();

                        log.info("WebSocket authentication successful: userId={}", userId);

                        // Create authentication object with userId as principal
                        UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                userId,  // Principal is userId (UUID string)
                                null,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                            );

                        // Set authentication in STOMP session
                        accessor.setUser(authentication);

                    } catch (Exception e) {
                        log.error("WebSocket JWT validation failed: {}", e.getMessage());
                        throw new IllegalArgumentException("Invalid JWT token");
                    }
                } else {
                    log.warn("WebSocket connection attempt without Bearer token");
                    throw new IllegalArgumentException("Authorization header must start with 'Bearer '");
                }
            } else {
                log.warn("WebSocket connection attempt without Authorization header");
                throw new IllegalArgumentException("Missing Authorization header");
            }
        }

        return message;
    }
}

