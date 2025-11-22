package com.yads.notificationservice.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Map;

/**
 * Test configuration for JWT decoding.
 * Provides a JwtDecoder that can validate JWT tokens signed with the test
 * secret key.
 */
@TestConfiguration
public class TestJwtDecoderConfig {

  private static final SecretKey TEST_SECRET = Keys.hmacShaKeyFor(
      "test-secret-key-for-websocket-integration-tests-minimum-256-bits".getBytes());

  @Bean
  @Primary
  public JwtDecoder jwtDecoder() {
    return token -> {
      try {
        var claims = Jwts.parser()
            .verifyWith(TEST_SECRET)
            .build()
            .parseSignedClaims(token);

        var body = claims.getPayload();

        // Build claims map with only non-null values
        Map<String, Object> claimsMap = new java.util.HashMap<>();
        claimsMap.put("sub", body.getSubject());

        if (body.get("email") != null) {
          claimsMap.put("email", body.get("email", String.class));
        }
        if (body.get("preferred_username") != null) {
          claimsMap.put("preferred_username", body.get("preferred_username", String.class));
        }
        if (body.getIssuedAt() != null) {
          claimsMap.put("iat", body.getIssuedAt().toInstant());
        }
        if (body.getExpiration() != null) {
          claimsMap.put("exp", body.getExpiration().toInstant());
        }

        return new Jwt(
            token,
            body.getIssuedAt() != null ? body.getIssuedAt().toInstant() : Instant.now(),
            body.getExpiration() != null ? body.getExpiration().toInstant() : Instant.now().plusSeconds(3600),
            Map.of("alg", "HS256", "typ", "JWT"),
            claimsMap);
      } catch (Exception e) {
        throw new JwtException("Invalid JWT token: " + e.getMessage(), e);
      }
    };
  }
}
