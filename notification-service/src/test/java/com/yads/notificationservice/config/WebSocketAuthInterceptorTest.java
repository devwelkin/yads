package com.yads.notificationservice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WebSocketAuthInterceptor.
 * Tests JWT validation and authentication in WebSocket STOMP connections.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

  @Mock
  private JwtDecoder jwtDecoder;

  @Mock
  private MessageChannel messageChannel;

  @InjectMocks
  private WebSocketAuthInterceptor webSocketAuthInterceptor;

  private UUID userId;
  private String validToken;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    validToken = "valid.jwt.token";
  }

  @Test
  void preSend_ValidJwtToken_AuthenticatesSuccessfully() {
    // Arrange
    Jwt jwt = createMockJwt(userId.toString());
    when(jwtDecoder.decode(anyString())).thenReturn(jwt);
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer " + validToken);
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act
    Message<?> result = webSocketAuthInterceptor.preSend(message, messageChannel);

    // Assert
    assertThat(result).isNotNull();
    verify(jwtDecoder).decode(validToken);

    StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
    assertThat(resultAccessor.getUser()).isNotNull();
    assertThat(resultAccessor.getUser().getName()).isEqualTo(userId.toString());
    assertThat(resultAccessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
  }

  @Test
  void preSend_InvalidJwtToken_ThrowsException() {
    // Arrange
    when(jwtDecoder.decode(validToken)).thenThrow(new JwtException("Invalid token"));

    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer " + validToken);
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act & Assert
    assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(message, messageChannel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid JWT token");
  }

  @Test
  void preSend_MissingAuthorizationHeader_ThrowsException() {
    // Arrange
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    // No Authorization header
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act & Assert
    assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(message, messageChannel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing Authorization header");

    verify(jwtDecoder, never()).decode(anyString());
  }

  @Test
  void preSend_EmptyAuthorizationHeader_ThrowsException() {
    // Arrange
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "");
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act & Assert
    assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(message, messageChannel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Authorization header must start with 'Bearer '");
  }

  @Test
  void preSend_AuthorizationHeaderWithoutBearer_ThrowsException() {
    // Arrange
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "NotBearer " + validToken);
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act & Assert
    assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(message, messageChannel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Authorization header must start with 'Bearer '");

    verify(jwtDecoder, never()).decode(anyString());
  }

  @Test
  void preSend_BearerTokenWithoutSpace_ThrowsException() {
    // Arrange
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer" + validToken); // Missing space
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act & Assert
    assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(message, messageChannel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Authorization header must start with 'Bearer '");
  }

  @Test
  void preSend_OnlyBearerKeyword_ThrowsException() {
    // Arrange
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer ");
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act & Assert - should fail at JWT decode with empty string
    when(jwtDecoder.decode("")).thenThrow(new JwtException("Empty token"));

    assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(message, messageChannel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid JWT token");
  }

  @Test
  void preSend_NonConnectCommand_PassesThrough() {
    // Arrange - using SUBSCRIBE command instead of CONNECT
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    // No Authorization header needed for non-CONNECT commands
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act
    Message<?> result = webSocketAuthInterceptor.preSend(message, messageChannel);

    // Assert
    assertThat(result).isNotNull();
    verify(jwtDecoder, never()).decode(anyString());
  }

  @Test
  void preSend_SendCommand_PassesThrough() {
    // Arrange
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act
    Message<?> result = webSocketAuthInterceptor.preSend(message, messageChannel);

    // Assert
    assertThat(result).isNotNull();
    verify(jwtDecoder, never()).decode(anyString());
  }

  @Test
  void preSend_DisconnectCommand_PassesThrough() {
    // Arrange
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act
    Message<?> result = webSocketAuthInterceptor.preSend(message, messageChannel);

    // Assert
    assertThat(result).isNotNull();
    verify(jwtDecoder, never()).decode(anyString());
  }

  @Test
  void preSend_MultipleAuthorizationHeaders_UsesFirstOne() {
    // Arrange
    Jwt jwt = createMockJwt(userId.toString());
    when(jwtDecoder.decode(anyString())).thenReturn(jwt);

    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer " + validToken);
    accessor.addNativeHeader("Authorization", "Bearer another.token");
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act
    Message<?> result = webSocketAuthInterceptor.preSend(message, messageChannel);

    // Assert
    assertThat(result).isNotNull();
    verify(jwtDecoder, atLeastOnce()).decode(anyString()); // Should decode a token
  }

  @Test
  void preSend_JwtDecoderThrowsRuntimeException_ThrowsIllegalArgumentException() {
    // Arrange
    when(jwtDecoder.decode(validToken)).thenThrow(new RuntimeException("Decoder error"));

    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer " + validToken);
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act & Assert
    assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(message, messageChannel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid JWT token");
  }

  @Test
  void preSend_ValidJwt_SetsUserWithCorrectAuthorities() {
    // Arrange
    Jwt jwt = createMockJwt(userId.toString());
    when(jwtDecoder.decode(anyString())).thenReturn(jwt);
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer " + validToken);
    Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    // Act
    Message<?> result = webSocketAuthInterceptor.preSend(message, messageChannel);

    // Assert
    StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
    UsernamePasswordAuthenticationToken auth = (UsernamePasswordAuthenticationToken) resultAccessor.getUser();

    assertThat(auth).isNotNull();
    assertThat(auth.getPrincipal()).isEqualTo(userId.toString());
    assertThat(auth.getCredentials()).isNull();
    assertThat(auth.getAuthorities()).hasSize(1);
    assertThat(auth.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_USER");
  }

  @Test
  void preSend_NullAccessor_PassesThrough() {
    // Arrange - create a message without STOMP headers
    Message<?> message = MessageBuilder.withPayload(new byte[0]).build();

    // Act
    Message<?> result = webSocketAuthInterceptor.preSend(message, messageChannel);

    // Assert
    assertThat(result).isNotNull();
    verify(jwtDecoder, never()).decode(anyString());
  }

  private Jwt createMockJwt(String subject) {
    return new Jwt(
        "token-value",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Map.of("alg", "RS256"),
        Map.of("sub", subject));
  }
}
