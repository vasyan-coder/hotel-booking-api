package com.acme.hotel.config;

import com.acme.common.JwtSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@OpenAPIDefinition(
    info = @Info(title = "Hotel Service", version = "1.0"),
    security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http, @Value("${security.jwt-secret}") String secret, ObjectMapper mapper)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(
                        "/api/rooms/allocate",
                        "/api/rooms/release",
                        "/api/rooms/*/confirm-availability",
                        "/api/rooms/*/release")
                    .hasRole("INTERNAL")
                    .requestMatchers(HttpMethod.GET, "/api/hotels/*/statistics")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/hotels/**", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/hotels/**", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/api/hotels/**", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/hotels/**", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .hasAnyRole("USER", "ADMIN"))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(
                        jwt ->
                            jwt.decoder(JwtSupport.decoder(secret))
                                .jwtAuthenticationConverter(JwtSupport.authorities()))
                    .authenticationEntryPoint(JwtSupport.authenticationEntryPoint(mapper)))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(JwtSupport.authenticationEntryPoint(mapper))
                    .accessDeniedHandler(JwtSupport.accessDeniedHandler(mapper)))
        .build();
  }
}
