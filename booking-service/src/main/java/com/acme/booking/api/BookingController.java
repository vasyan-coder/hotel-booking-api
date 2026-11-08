package com.acme.booking.api;

import com.acme.booking.api.ApiModels.*;
import com.acme.booking.domain.BookingStatus;
import com.acme.booking.integration.*;
import com.acme.booking.security.Actor;
import com.acme.booking.service.BookingService;
import com.acme.booking.service.BookingTransactions;
import com.acme.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@Validated
public class BookingController {
  private final BookingService bookings;
  private final HotelClient hotel;
  private final BookingTransactions transactions;

  public BookingController(
      BookingService bookings, HotelClient hotel, BookingTransactions transactions) {
    this.bookings = bookings;
    this.hotel = hotel;
    this.transactions = transactions;
  }

  @PostMapping
  public ResponseEntity<BookingView> create(
      Authentication authentication, @Valid @RequestBody CreateBookingRequest request) {
    BookingService.Creation result = bookings.create(Actor.from(authentication), request);
    HttpStatus status =
        result.booking().status() == BookingStatus.PENDING
            ? HttpStatus.ACCEPTED
            : (result.created() ? HttpStatus.CREATED : HttpStatus.OK);
    return ResponseEntity.status(status).body(result.booking());
  }

  @GetMapping
  public Page<BookingView> list(
      Authentication authentication,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    return bookings.list(Actor.from(authentication), pageable);
  }

  @GetMapping("/{id}")
  public BookingView get(Authentication authentication, @PathVariable UUID id) {
    return bookings.get(id, Actor.from(authentication));
  }

  @DeleteMapping("/{id}")
  public BookingView cancel(Authentication authentication, @PathVariable UUID id) {
    return bookings.cancel(id, Actor.from(authentication));
  }

  @GetMapping("/recommendations")
  public JsonNode recommendations(
      JwtAuthenticationToken authentication,
      @RequestParam @Positive Long hotelId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    transactions.assertActive(Actor.from(authentication));
    if (startDate.isBefore(LocalDate.now()) || !endDate.isAfter(startDate)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_DATES",
          "Dates must satisfy today <= startDate < endDate");
    }
    try {
      return hotel.recommendations(
          hotelId, startDate, endDate, page, size, authentication.getToken().getTokenValue());
    } catch (HotelCallException exception) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "HOTEL_UNAVAILABLE", "Hotel service unavailable");
    }
  }
}
