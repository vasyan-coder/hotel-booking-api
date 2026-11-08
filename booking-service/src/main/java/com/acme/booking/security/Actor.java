package com.acme.booking.security;

import com.acme.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

public record Actor(Long id, boolean admin) {
  public static Actor from(Authentication authentication) {
    try {
      return new Actor(
          Long.parseLong(authentication.getName()),
          authentication.getAuthorities().stream()
              .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")));
    } catch (NumberFormatException exception) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SUBJECT", "User token required");
    }
  }
}
