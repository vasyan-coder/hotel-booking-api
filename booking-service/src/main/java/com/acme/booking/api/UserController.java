package com.acme.booking.api;

import com.acme.booking.api.ApiModels.*;
import com.acme.booking.service.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
  private final UserService users;
  private final BookingService bookings;

  public UserController(UserService users, BookingService bookings) {
    this.users = users;
    this.bookings = bookings;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public UserView create(@Valid @RequestBody CreateUserRequest request) {
    return users.create(request);
  }

  @GetMapping
  public Page<UserView> list(@PageableDefault(sort = "id") Pageable pageable) {
    return users.list(pageable);
  }

  @GetMapping("/{id}")
  public UserView get(@PathVariable Long id) {
    return users.get(id);
  }

  @PatchMapping("/{id}")
  public UserView update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
    return users.update(id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    users.deactivate(id);
    bookings.cancelForUser(id);
  }
}
