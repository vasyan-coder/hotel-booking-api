package com.acme.booking.service;

import com.acme.booking.api.ApiModels.*;
import com.acme.booking.domain.BookingStatus;
import com.acme.booking.integration.*;
import com.acme.booking.security.Actor;
import com.acme.common.ApiException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BookingService {
  private static final Logger log = LoggerFactory.getLogger(BookingService.class);
  private final BookingTransactions transactions;
  private final HotelClient hotel;

  public BookingService(BookingTransactions transactions, HotelClient hotel) {
    this.transactions = transactions;
    this.hotel = hotel;
  }

  public record Creation(BookingView booking, boolean created) {}

  public Creation create(Actor actor, CreateBookingRequest request) {
    BookingTransactions.Reservation reservation = transactions.reserve(actor.id(), request);
    BookingView booking = reservation.booking();
    if (!reservation.created()) {
      return new Creation(booking, false);
    }
    log.atInfo()
        .addKeyValue("bookingId", booking.id())
        .addKeyValue("status", "PENDING")
        .log("Booking created");
    try {
      Long roomId = hotel.allocate(reservation.allocation());
      BookingView confirmed = transactions.confirm(booking.id(), roomId);
      if (confirmed.status() == BookingStatus.CANCELLED) {
        compensate(confirmed);
      }
      log.atInfo()
          .addKeyValue("bookingId", booking.id())
          .addKeyValue("status", confirmed.status())
          .log("Booking allocation completed");
      return new Creation(confirmed, true);
    } catch (RuntimeException exception) {
      HttpStatus status =
          exception instanceof HotelCallException remote
              ? remote.getStatus()
              : HttpStatus.SERVICE_UNAVAILABLE;
      String reason =
          status == HttpStatus.CONFLICT
              ? "ROOM_UNAVAILABLE"
              : (status.is4xxClientError() ? "INVALID_HOTEL_REQUEST" : "HOTEL_UNAVAILABLE");
      compensate(transactions.cancel(booking.id(), reason, null));
      log.atWarn()
          .addKeyValue("bookingId", booking.id())
          .addKeyValue("reason", reason)
          .log("Booking cancelled after allocation failure");
      throw new ApiException(
          status,
          reason,
          "Booking "
              + booking.id()
              + " cancelled; retrieve it or retry the same requestId to inspect the result");
    }
  }

  public BookingView get(UUID id, Actor actor) {
    return transactions.get(id, actor);
  }

  public Page<BookingView> list(Actor actor, Pageable pageable) {
    return transactions.list(actor, pageable);
  }

  public BookingView cancel(UUID id, Actor actor) {
    BookingView cancelled = transactions.cancel(id, "USER_CANCELLED", actor);
    compensate(cancelled);
    return transactions.get(id, actor);
  }

  public void cancelForUser(Long userId) {
    for (UUID id : transactions.activeForUser(userId)) {
      compensate(transactions.cancel(id, "USER_DELETED", null));
    }
  }

  public void compensate(BookingView booking) {
    if (!booking.compensationRequired()) {
      return;
    }
    try {
      hotel.release(HotelClient.Release.from(booking));
      transactions.compensated(booking.id());
      log.atInfo().addKeyValue("bookingId", booking.id()).log("Booking compensation completed");
    } catch (RuntimeException exception) {
      log.atWarn()
          .addKeyValue("bookingId", booking.id())
          .addKeyValue("error", exception.getClass().getSimpleName())
          .log("Booking compensation deferred for recovery");
    }
  }
}
