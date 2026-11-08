package com.acme.booking.config;

import com.acme.booking.domain.*;
import com.acme.booking.persistence.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.seed-data", havingValue = "true")
public class DemoData implements ApplicationRunner {
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final String adminPassword;
  private final String userPassword;

  public DemoData(
      UserRepository users,
      PasswordEncoder passwords,
      @Value("${app.demo.admin-password}") String adminPassword,
      @Value("${app.demo.user-password}") String userPassword) {
    this.users = users;
    this.passwords = passwords;
    this.adminPassword = adminPassword;
    this.userPassword = userPassword;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    seed("admin", adminPassword, Role.ADMIN);
    seed("user", userPassword, Role.USER);
  }

  private void seed(String name, String password, Role role) {
    if (users.findByUsername(name).isEmpty()) {
      users.save(new User(name, passwords.encode(password), role));
    }
  }
}
