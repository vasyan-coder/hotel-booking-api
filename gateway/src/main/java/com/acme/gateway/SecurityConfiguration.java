package com.acme.gateway;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {
  @Bean
  ReactiveJwtDecoder jwtDecoder(@Value("${security.jwt-secret}") String secret) {
    byte[] key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32) {
      throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
    }
    var decoder =
        NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    OAuth2TokenValidator<Jwt> requiredClaims =
        jwt -> {
          if (jwt.getAudience() == null
              || !jwt.getAudience().contains("hotel-api")
              || jwt.getExpiresAt() == null
              || jwt.getSubject() == null
              || jwt.getSubject().isBlank()) {
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(
                    "invalid_token", "Required JWT claims are missing or invalid", null));
          }
          return OAuth2TokenValidatorResult.success();
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("hotel-booking"), requiredClaims));
    return decoder;
  }

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, ErrorResponseWriter errors) {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);

    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .authorizeExchange(
            exchange ->
                exchange
                    .pathMatchers(
                        "/api/internal/**",
                        "/internal/**",
                        "/api/rooms/allocate",
                        "/api/rooms/release",
                        "/api/rooms/*/confirm-availability",
                        "/api/rooms/*/release")
                    .denyAll()
                    .pathMatchers("/api/users/**")
                    .hasRole("ADMIN")
                    .pathMatchers(HttpMethod.GET, "/api/hotels/*/statistics")
                    .hasRole("ADMIN")
                    .pathMatchers(HttpMethod.POST, "/api/hotels/**", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .pathMatchers(HttpMethod.PUT, "/api/hotels/**", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .pathMatchers(HttpMethod.PATCH, "/api/hotels/**", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .pathMatchers(HttpMethod.DELETE, "/api/hotels/**", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .pathMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register")
                    .permitAll()
                    .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyExchange()
                    .hasAnyRole("USER", "ADMIN"))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (exchange, exception) -> {
                          exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
                          return errors.write(
                              exchange,
                              HttpStatus.UNAUTHORIZED,
                              "UNAUTHORIZED",
                              "A valid bearer token is required");
                        })
                    .accessDeniedHandler(
                        (exchange, exception) ->
                            errors.write(
                                exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied")))
        .oauth2ResourceServer(
            resource ->
                resource
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(converter)))
                    .authenticationEntryPoint(
                        (exchange, exception) -> {
                          exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
                          return errors.write(
                              exchange,
                              HttpStatus.UNAUTHORIZED,
                              "UNAUTHORIZED",
                              "A valid bearer token is required");
                        }))
        .build();
  }
}
