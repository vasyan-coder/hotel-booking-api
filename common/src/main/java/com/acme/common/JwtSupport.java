package com.acme.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

public final class JwtSupport {
  public static final String ISSUER = "hotel-booking";
  public static final String AUDIENCE = "hotel-api";

  private JwtSupport() {}

  private static SecretKey key(String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32)
      throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  public static JwtDecoder decoder(String secret) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(ISSUER),
            jwt ->
                jwt.getAudience() != null
                        && jwt.getAudience().contains(AUDIENCE)
                        && jwt.getExpiresAt() != null
                        && jwt.getSubject() != null
                        && !jwt.getSubject().isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Invalid token audience", null))));
    return decoder;
  }

  public static JwtEncoder encoder(String secret) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
  }

  public static JwtAuthenticationConverter authorities() {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return converter;
  }

  public static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper mapper) {
    return (request, response, e) -> {
      response.setStatus(401);
      response.setHeader("WWW-Authenticate", "Bearer");
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      mapper.writeValue(
          response.getOutputStream(),
          new ErrorDTO(
              Instant.now(),
              401,
              "UNAUTHORIZED",
              "Authentication required or token invalid",
              request.getRequestURI(),
              MDC.get("traceId"),
              Map.of()));
    };
  }

  public static AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
    return (request, response, e) -> {
      response.setStatus(403);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      mapper.writeValue(
          response.getOutputStream(),
          new ErrorDTO(
              Instant.now(),
              403,
              "FORBIDDEN",
              "Access denied",
              request.getRequestURI(),
              MDC.get("traceId"),
              Map.of()));
    };
  }
}
