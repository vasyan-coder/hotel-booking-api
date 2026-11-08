package com.acme.booking.api;

import com.acme.booking.api.ApiModels.*;
import com.acme.booking.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirements
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final UserService users;

  public AuthController(UserService users) {
    this.users = users;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
    return users.register(request);
  }

  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return users.login(request);
  }
}
