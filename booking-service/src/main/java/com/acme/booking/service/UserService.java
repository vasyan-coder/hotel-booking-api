package com.acme.booking.service;

import com.acme.booking.api.ApiModels.*;
import com.acme.booking.domain.*;
import com.acme.booking.persistence.UserRepository;
import com.acme.booking.security.TokenService;
import com.acme.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final TokenService tokens;

  public UserService(UserRepository users, PasswordEncoder passwords, TokenService tokens) {
    this.users = users;
    this.passwords = passwords;
    this.tokens = tokens;
  }

  @Transactional
  public TokenResponse register(RegisterRequest request) {
    return token(create(request.username(), request.password(), Role.USER));
  }

  @Transactional(readOnly = true)
  public TokenResponse login(LoginRequest request) {
    User user =
        users
            .findByUsername(request.username())
            .filter(User::isActive)
            .filter(found -> passwords.matches(request.password(), found.getPassword()))
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_CREDENTIALS",
                        "Invalid username or password"));
    return token(user);
  }

  @Transactional
  public UserView create(CreateUserRequest request) {
    return UserView.from(create(request.username(), request.password(), request.role()));
  }

  private User create(String username, String password, Role role) {
    if (users.findByUsername(username).isPresent()) {
      throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "Username already exists");
    }
    try {
      return users.saveAndFlush(new User(username, passwords.encode(password), role));
    } catch (DataIntegrityViolationException exception) {
      throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "Username already exists");
    }
  }

  @Transactional(readOnly = true)
  public Page<UserView> list(Pageable pageable) {
    com.acme.booking.api.PageValidation.validate(
        pageable, java.util.Set.of("id", "username", "role", "active"));
    return users.findAll(pageable).map(UserView::from);
  }

  @Transactional(readOnly = true)
  public UserView get(Long id) {
    return UserView.from(required(id));
  }

  @Transactional
  public UserView update(Long id, UpdateUserRequest request) {
    User user = users.lockById(id).orElseThrow(() -> missing(id));
    if (!user.isActive()) {
      throw new ApiException(HttpStatus.CONFLICT, "USER_DEACTIVATED", "User is deactivated");
    }
    if (request.password() != null) {
      user.setPassword(passwords.encode(request.password()));
    }
    if (request.role() != null) {
      user.setRole(request.role());
    }
    return UserView.from(user);
  }

  @Transactional
  public void deactivate(Long id) {
    users.lockById(id).orElseThrow(() -> missing(id)).deactivate();
  }

  private User required(Long id) {
    return users.findById(id).orElseThrow(() -> missing(id));
  }

  private ApiException missing(Long id) {
    return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User " + id + " not found");
  }

  private TokenResponse token(User user) {
    return new TokenResponse(tokens.forUser(user), "Bearer", 3600, UserView.from(user));
  }
}
