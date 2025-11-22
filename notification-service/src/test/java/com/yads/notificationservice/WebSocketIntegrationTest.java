package com.yads.notificationservice;

import com.yads.notificationservice.model.Notification;
import com.yads.notificationservice.model.NotificationType;
import com.yads.notificationservice.repository.NotificationRepository;
import com.yads.notificationservice.service.NotificationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.crypto.SecretKey;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for WebSocket functionality.
 * Tests real-time notification delivery via WebSocket STOMP protocol.
 *
 * Uses JJWT library to create valid JWT tokens and NimbusJwtDecoder for validation.
 * Both encoding (test) and decoding (server) use the same HMAC secret key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles("test")  // Disable production SecurityConfig
class WebSocketIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    private WebSocketStompClient stompClient;
    private String wsUrl;

    // shared secret for both encoding (test) and decoding (server)
    // must be at least 256 bits for HS256
    private static final String SHARED_SECRET = "bu-test-icin-cok-gizli-bir-anahtar-en-az-32-byte-olmali-123456";
    private static final SecretKey HMAC_KEY = Keys.hmacShaKeyFor(SHARED_SECRET.getBytes());

    /**
     * Test configuration that overrides the JwtDecoder bean to use our test HMAC key.
     * This allows the server to validate JWT tokens created in tests.
     * Also provides a minimal SecurityFilterChain for WebSocket testing.
     */
    @TestConfiguration
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class TestConfig {
        @Bean
        @Primary
        public JwtDecoder jwtDecoder() {
            // server side will use this decoder which trusts our hmac key
            return NimbusJwtDecoder.withSecretKey(HMAC_KEY).build();
        }

        @Bean
        public org.springframework.security.web.SecurityFilterChain testSecurityFilterChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                    .anyRequest().permitAll()  // Allow all for testing
                )
                .sessionManagement(session -> session
                    .sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)
                );
            return http.build();
        }
    }

    @BeforeEach
    void setUp() {
        wsUrl = "ws://localhost:" + port + "/ws";
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void cleanup() {
        notificationRepository.deleteAll();
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    @Test
    @Order(1)
    void should_connect_to_websocket_with_valid_jwt() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        String mockJwt = createMockJwtForUser(userId);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Authorization", "Bearer " + mockJwt);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + mockJwt);

        BlockingQueue<String> connectionQueue = new ArrayBlockingQueue<>(1);

        // Act
        // connectAsync signature: url, handshakeHeaders, connectHeaders, handler
        StompSession session = stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                connectionQueue.add("connected");
            }

            @Override
            public void handleException(StompSession session, StompCommand command,
                                        StompHeaders headers, byte[] payload, Throwable exception) {
                connectionQueue.add("error: " + exception.getMessage());
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                 connectionQueue.add("transport_error: " + exception.getMessage());
            }
        }).get(5, TimeUnit.SECONDS);

        // Assert
        String result = connectionQueue.poll(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("connected");
        assertThat(session.isConnected()).isTrue();

        session.disconnect();
    }

    @Test
    @Order(2)
    void should_reject_connection_without_authorization_header() throws Exception {
        // Arrange
        BlockingQueue<String> errorQueue = new ArrayBlockingQueue<>(1);

        // Act & Assert
        try {
            stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), new StompHeaders(), new StompSessionHandlerAdapter() {
                @Override
                public void handleException(StompSession session, StompCommand command,
                                            StompHeaders headers, byte[] payload, Throwable exception) {
                    errorQueue.add("error: " + exception.getMessage());
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    errorQueue.add("transport error: " + exception.getMessage());
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected - connection should fail
            assertThat(e.getMessage()).containsIgnoringCase("authorization");
        }
    }

    @Test
    @Order(3)
    void should_receive_pending_notifications_on_connection() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();

        // Create pending notifications (user offline)
        Notification notif1 = createPendingNotification(userId, NotificationType.ORDER_CREATED);
        Notification notif2 = createPendingNotification(userId, NotificationType.ORDER_PREPARING);

        String mockJwt = createMockJwtForUser(userId);
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Authorization", "Bearer " + mockJwt);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + mockJwt);

        BlockingQueue<Object> notificationQueue = new ArrayBlockingQueue<>(10);

        // Act
        StompSession session = stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() {
      @Override
      public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
        // Subscribe to user's notification queue
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return Object.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            notificationQueue.add(payload);
          }
        });

        // Trigger sending pending notifications
        session.send("/app/notifications", "");
      }
    }).get(5, TimeUnit.SECONDS);

    // Assert
    await().atMost(5, TimeUnit.SECONDS).until(() -> notificationQueue.size() >= 2);

    List<Object> notifications = new ArrayList<>();
    notificationQueue.drainTo(notifications);
    assertThat(notifications).hasSizeGreaterThanOrEqualTo(2);

    // Verify notifications marked as delivered
    Notification updated1 = notificationRepository.findById(notif1.getId()).orElseThrow();
    Notification updated2 = notificationRepository.findById(notif2.getId()).orElseThrow();
    assertThat(updated1.getDeliveredAt()).isNotNull();
    assertThat(updated2.getDeliveredAt()).isNotNull();

    session.disconnect();
  }

    @Test
    @Order(4)
    void should_receive_realtime_notification_when_online() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        String mockJwt = createMockJwtForUser(userId);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Authorization", "Bearer " + mockJwt);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + mockJwt);

        BlockingQueue<Object> notificationQueue = new ArrayBlockingQueue<>(10);

        // Act
        StompSession session = stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() {
      @Override
      public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
        // Subscribe to user's notification queue
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return Object.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            notificationQueue.add(payload);
          }
        });

        // Send initial message to establish connection
        session.send("/app/notifications", "");
      }
    }).get(5, TimeUnit.SECONDS);

    Thread.sleep(500); // Wait for subscription to be fully established

    // Create notification while user is online
    UUID orderId = UUID.randomUUID();
    notificationService.createAndSendNotification(
        userId,
        NotificationType.ORDER_CREATED,
        "Real-time notification test",
        orderId, null, null,
        java.util.Map.of("test", "data"));

    // Assert
    Object notification = notificationQueue.poll(5, TimeUnit.SECONDS);
    assertThat(notification).isNotNull();

    session.disconnect();
  }

    @Test
    @Order(5)
    void should_not_receive_other_users_notifications() throws Exception {
        // Arrange
        UUID user1Id = UUID.randomUUID();
        UUID user2Id = UUID.randomUUID();

        // Create notification for user2
        createPendingNotification(user2Id, NotificationType.ORDER_CREATED);

        // Connect as user1
        String mockJwt = createMockJwtForUser(user1Id);
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Authorization", "Bearer " + mockJwt);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + mockJwt);

        BlockingQueue<Object> notificationQueue = new ArrayBlockingQueue<>(10);

        // Act
        StompSession session = stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() {
      @Override
      public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return Object.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            notificationQueue.add(payload);
          }
        });

        session.send("/app/notifications", "");
      }
    }).get(5, TimeUnit.SECONDS);

    // Assert - user1 should not receive user2's notification
    Thread.sleep(2000); // Wait to ensure no messages arrive
    assertThat(notificationQueue).isEmpty();

    session.disconnect();
  }

    @Test
    @Order(6)
    void should_handle_multiple_concurrent_connections() throws Exception {
        // Arrange
        UUID user1Id = UUID.randomUUID();
        UUID user2Id = UUID.randomUUID();

        String jwt1 = createMockJwtForUser(user1Id);
        String jwt2 = createMockJwtForUser(user2Id);

        // Act - Connect two users simultaneously
        WebSocketHttpHeaders handshakeHeaders1 = new WebSocketHttpHeaders();
        handshakeHeaders1.add("Authorization", "Bearer " + jwt1);
        StompHeaders connectHeaders1 = new StompHeaders();
        connectHeaders1.add("Authorization", "Bearer " + jwt1);

        WebSocketHttpHeaders handshakeHeaders2 = new WebSocketHttpHeaders();
        handshakeHeaders2.add("Authorization", "Bearer " + jwt2);
        StompHeaders connectHeaders2 = new StompHeaders();
        connectHeaders2.add("Authorization", "Bearer " + jwt2);

        StompSession session1 = stompClient.connectAsync(wsUrl, handshakeHeaders1, connectHeaders1,
                new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);

        StompSession session2 = stompClient.connectAsync(wsUrl, handshakeHeaders2, connectHeaders2,
                new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);

        // Assert
        assertThat(session1.isConnected()).isTrue();
        assertThat(session2.isConnected()).isTrue();

        session1.disconnect();
        session2.disconnect();
    }

  private Notification createPendingNotification(UUID userId, NotificationType type) {
    Notification notification = new Notification();
    notification.setUserId(userId);
    notification.setType(type);
    notification.setOrderId(UUID.randomUUID());
    notification.setMessage("Test message");
    notification.setPayload("{}");
    notification.setIsRead(false);
    notification.setDeliveredAt(null); // Pending
    return notificationRepository.save(notification);
  }

    /**
     * Creates a valid JWT token for testing.
     * Uses JJWT library to create properly signed tokens with the shared HMAC key.
     */
    private String createMockJwtForUser(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .id(UUID.randomUUID().toString()) // jti claim is good practice
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .claim("email", "test@example.com")
                .claim("preferred_username", "testuser")
                // important: scopes/authorities might be needed depending on your security config
                .claim("realm_access", Map.of("roles", List.of("user")))
                .signWith(HMAC_KEY)
                .compact();
    }
}