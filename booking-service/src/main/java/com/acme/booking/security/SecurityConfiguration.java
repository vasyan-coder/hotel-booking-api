package com.acme.booking.security;

import com.acme.common.JwtSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  JwtDecoder jwtDecoder(@Value("${security.jwt-secret}") String secret) {
    return JwtSupport.decoder(secret);
  }

  @Bean
  JwtEncoder jwtEncoder(@Value("${security.jwt-secret}") String secret) {
    return JwtSupport.encoder(secret);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/auth/**",
                        "/actuator/health",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/api/users/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .hasAnyRole("USER", "ADMIN"))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(JwtSupport.authenticationEntryPoint(mapper))
                    .accessDeniedHandler(JwtSupport.accessDeniedHandler(mapper)))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtSupport.authorities()))
                    .authenticationEntryPoint(JwtSupport.authenticationEntryPoint(mapper))
                    .accessDeniedHandler(JwtSupport.accessDeniedHandler(mapper)))
        .build();
  }
}
