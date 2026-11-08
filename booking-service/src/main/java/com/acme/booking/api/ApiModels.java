package com.acme.booking.api;

import com.acme.booking.domain.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.UUID;

public final class ApiModels {
  private ApiModels() {}

  public record RegisterRequest(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,64}") String username,
      @NotBlank @Size(min = 8, max = 72) String password) {}

  public record LoginRequest(
      @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password) {}

  public record TokenResponse(
      String accessToken, String tokenType, long expiresIn, UserView user) {}

  public record CreateUserRequest(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,64}") String username,
      @NotBlank @Size(min = 8, max = 72) String password,
      @NotNull Role role) {}

  public record UpdateUserRequest(@Size(min = 8, max = 72) String password, Role role) {}

  public record UserView(Long id, String username, Role role, boolean active) {
    public static UserView from(User user) {
      return new UserView(user.getId(), user.getUsername(), user.getRole(), user.isActive());
    }
  }

  public record CreateBookingRequest(
      @NotNull UUID requestId,
      @NotNull @Positive Long hotelId,
      Long roomId,
      boolean autoSelect,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate) {}

  public record BookingView(
      UUID id,
      UUID requestId,
      Long userId,
      Long hotelId,
      Long roomId,
      boolean autoSelect,
      LocalDate startDate,
      LocalDate endDate,
      BookingStatus status,
      Instant createdAt,
      Instant updatedAt,
      boolean compensationRequired,
      String failureCode) {
    public static BookingView from(Booking booking) {
      return new BookingView(
          booking.getId(),
          booking.getRequestId(),
          booking.getUser().getId(),
          booking.getHotelId(),
          booking.getRoomId(),
          booking.isAutoSelect(),
          booking.getStartDate(),
          booking.getEndDate(),
          booking.getStatus(),
          booking.getCreatedAt(),
          booking.getUpdatedAt(),
          booking.isCompensationRequired(),
          booking.getFailureCode());
    }
  }
}
