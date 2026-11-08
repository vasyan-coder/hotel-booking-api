package com.acme.booking.service;

import com.acme.booking.api.ApiModels.*;
import com.acme.booking.domain.*;
import com.acme.booking.integration.HotelClient.Allocation;
import com.acme.booking.persistence.*;
import com.acme.booking.security.Actor;
import com.acme.common.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All methods end their local transaction before any Hotel call starts. */
@Service
public class BookingTransactions {
  private final BookingRepository bookings;
  private final UserRepository users;

  public BookingTransactions(BookingRepository bookings, UserRepository users) {
    this.bookings = bookings;
    this.users = users;
  }

  public record Reservation(BookingView booking, Allocation allocation, boolean created) {}

  @Transactional
  public Reservation reserve(Long userId, CreateBookingRequest request) {
    User user =
        users
            .lockById(userId)
            .filter(User::isActive)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.UNAUTHORIZED, "USER_INACTIVE", "Active user required"));
    Optional<Booking> existing = bookings.findByUserIdAndRequestId(userId, request.requestId());
    if (existing.isPresent()) {
      Booking booking = existing.get();
      assertSameRequest(booking, request);
      return new Reservation(BookingView.from(booking), allocation(booking), false);
    }
    validate(request);
    Booking booking =
        bookings.saveAndFlush(
            new Booking(
                user,
                request.requestId(),
                request.hotelId(),
                request.roomId(),
                request.autoSelect(),
                request.startDate(),
                request.endDate()));
    return new Reservation(BookingView.from(booking), allocation(booking), true);
  }

  @Transactional
  public BookingView confirm(UUID id, Long roomId) {
    Booking booking = locked(id);
    if (booking.getStatus() == BookingStatus.PENDING) {
      booking.confirm(roomId);
    }
    return BookingView.from(booking);
  }

  @Transactional
  public BookingView cancel(UUID id, String reason, Actor actor) {
    Booking booking = locked(id);
    if (actor != null) {
      assertActive(actor);
      assertOwner(booking, actor);
    }
    if (booking.getStatus() != BookingStatus.CANCELLED) {
      booking.cancel(reason);
    }
    return BookingView.from(booking);
  }

  @Transactional
  public Optional<BookingView> expirePending(UUID id, Instant cutoff) {
    Booking booking = locked(id);
    if (booking.getStatus() != BookingStatus.PENDING || !booking.getCreatedAt().isBefore(cutoff)) {
      return Optional.empty();
    }
    booking.cancel("PENDING_EXPIRED");
    return Optional.of(BookingView.from(booking));
  }

  @Transactional
  public void compensated(UUID id) {
    locked(id).compensationCompleted();
  }

  @Transactional(readOnly = true)
  public BookingView get(UUID id, Actor actor) {
    assertActive(actor);
    Booking booking = bookings.findById(id).orElseThrow(() -> missing(id));
    assertOwner(booking, actor);
    return BookingView.from(booking);
  }

  @Transactional(readOnly = true)
  public Page<BookingView> list(Actor actor, Pageable pageable) {
    assertActive(actor);
    com.acme.booking.api.PageValidation.validate(
        pageable, Set.of("id", "createdAt", "startDate", "endDate", "status", "hotelId", "roomId"));
    return bookings.findByUserId(actor.id(), pageable).map(BookingView::from);
  }

  @Transactional(readOnly = true)
  public List<UUID> activeForUser(Long id) {
    return bookings.findByUserIdAndStatusNot(id, BookingStatus.CANCELLED).stream()
        .map(Booking::getId)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<UUID> pendingBefore(Instant cutoff) {
    return bookings
        .findByStatusAndCreatedAtBefore(BookingStatus.PENDING, cutoff, PageRequest.of(0, 100))
        .stream()
        .map(Booking::getId)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<BookingView> compensations() {
    return bookings.findByCompensationRequiredTrue(PageRequest.of(0, 100)).stream()
        .map(BookingView::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public void assertActive(Actor actor) {
    if (users.findById(actor.id()).filter(User::isActive).isEmpty()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "USER_INACTIVE", "Active user required");
    }
  }

  private void validate(CreateBookingRequest request) {
    if (request.startDate().isBefore(LocalDate.now())
        || !request.endDate().isAfter(request.startDate())) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_DATES",
          "Dates must satisfy today <= startDate < endDate");
    }
    if (!request.autoSelect() && (request.roomId() == null || request.roomId() <= 0)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "ROOM_REQUIRED",
          "Positive roomId is required unless autoSelect is true");
    }
  }

  private void assertSameRequest(Booking booking, CreateBookingRequest request) {
    boolean equal =
        Objects.equals(booking.getHotelId(), request.hotelId())
            && booking.isAutoSelect() == request.autoSelect()
            && Objects.equals(
                booking.getRequestedRoomId(), request.autoSelect() ? null : request.roomId())
            && booking.getStartDate().equals(request.startDate())
            && booking.getEndDate().equals(request.endDate());
    if (!equal) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_CONFLICT",
          "requestId already belongs to a different request");
    }
  }

  private void assertOwner(Booking booking, Actor actor) {
    if (!actor.admin() && !booking.getUser().getId().equals(actor.id())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found");
    }
  }

  private Booking locked(UUID id) {
    return bookings.lockById(id).orElseThrow(() -> missing(id));
  }

  private ApiException missing(UUID id) {
    return new ApiException(
        HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking " + id + " not found");
  }

  private Allocation allocation(Booking booking) {
    return new Allocation(
        booking.getId(),
        booking.getHotelId(),
        booking.getRequestedRoomId(),
        booking.isAutoSelect(),
        booking.getStartDate(),
        booking.getEndDate());
  }
}
