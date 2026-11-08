package com.acme.discovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class RegistrySecurity {
  @Bean
  SecurityFilterChain registryFilterChain(
      HttpSecurity http, @Value("${registry.security.enabled:false}") boolean enabled)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (enabled) {
      http.authorizeHttpRequests(
              requests ->
                  requests
                      .requestMatchers("/actuator/health", "/actuator/health/**")
                      .permitAll()
                      .anyRequest()
                      .authenticated())
          .httpBasic(Customizer.withDefaults());
    } else {
      http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
          .httpBasic(basic -> basic.disable());
    }
    return http.build();
  }

  @Bean
  @ConditionalOnProperty(name = "registry.security.enabled", havingValue = "true")
  UserDetailsService registryUsers(
      @Value("${registry.security.username}") String username,
      @Value("${registry.security.password}") String password) {
    if (username.isBlank() || password.length() < 12) {
      throw new IllegalArgumentException(
          "Eureka credentials require a username and a 12-character password");
    }
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername(username)
            .password("{bcrypt}" + encoder.encode(password))
            .roles("REGISTRY")
            .build());
  }
}
