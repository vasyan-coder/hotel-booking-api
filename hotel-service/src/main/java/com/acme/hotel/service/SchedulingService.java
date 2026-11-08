package com.acme.hotel.service;

import com.acme.common.ApiException;
import com.acme.hotel.domain.*;
import com.acme.hotel.repository.*;
import com.acme.hotel.web.ApiModels.*;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulingService {
  private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final ReservationRepository reservations;

  public SchedulingService(
      HotelRepository hotels, RoomRepository rooms, ReservationRepository reservations) {
    this.hotels = hotels;
    this.rooms = rooms;
    this.reservations = reservations;
  }

  @Transactional
  public AllocationResponse allocate(AllocationRequest request) {
    validateDates(request.startDate(), request.endDate());
    // Every allocator and compensator for a hotel takes this database lock.
    Hotel hotel = lockHotel(request.hotelId());
    Reservation previous = reservations.findById(request.bookingId()).orElse(null);
    if (previous != null) {
      return replay(previous, request);
    }
    if (request.startDate().isBefore(LocalDate.now())) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_DATES", "Check-in must not be in the past");
    }
    Room room = selectRoom(request);
    room.recordBooking();
    reservations.saveAndFlush(
        new Reservation(
            request.bookingId(),
            hotel,
            room,
            request.startDate(),
            request.endDate(),
            request.autoSelect()));
    log.info(
        "event=room_reserved bookingId={} hotelId={} roomId={}",
        request.bookingId(),
        hotel.getId(),
        room.getId());
    return new AllocationResponse(room.getId());
  }

  @Transactional
  public void release(ReleaseRequest request) {
    Hotel hotel = lockHotel(request.hotelId());
    Reservation reservation = reservations.findById(request.bookingId()).orElse(null);
    if (reservation == null) {
      // Persist cancellation even if confirmation has not arrived yet: a late retry cannot
      // resurrect it.
      reservations.saveAndFlush(Reservation.tombstone(request.bookingId(), hotel));
    } else {
      if (!reservation.getHotel().getId().equals(request.hotelId())
          || (request.roomId() != null
              && reservation.getRoom() != null
              && !request.roomId().equals(reservation.getRoom().getId()))) {
        throw conflict(
            "IDEMPOTENCY_CONFLICT", "Booking identity does not match the stored reservation");
      }
      reservation.release();
    }
    log.info("event=room_released bookingId={} hotelId={}", request.bookingId(), request.hotelId());
  }

  private AllocationResponse replay(Reservation previous, AllocationRequest request) {
    if (previous.getStatus() == Reservation.Status.RELEASED) {
      throw conflict("RESERVATION_RELEASED", "A released booking cannot be confirmed again");
    }
    boolean matches =
        previous.getHotel().getId().equals(request.hotelId())
            && previous.getStartDate().equals(request.startDate())
            && previous.getEndDate().equals(request.endDate())
            && previous.isAutoSelect() == request.autoSelect()
            && (request.autoSelect()
                || Objects.equals(previous.getRoom().getId(), request.roomId()));
    if (!matches) {
      throw conflict(
          "IDEMPOTENCY_CONFLICT", "Booking id has already been used with a different payload");
    }
    return new AllocationResponse(previous.getRoom().getId());
  }

  private Room selectRoom(AllocationRequest request) {
    if (request.autoSelect()) {
      return rooms
          .findByHotelIdAndAvailableTrueOrderByTimesBookedAscIdAsc(request.hotelId())
          .stream()
          .filter(
              room -> !reservations.overlaps(room.getId(), request.startDate(), request.endDate()))
          .findFirst()
          .orElseThrow(
              () -> conflict("NO_AVAILABILITY", "No available room for the requested dates"));
    }
    if (request.roomId() == null) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "ROOM_REQUIRED", "roomId is required when autoSelect is false");
    }
    Room room =
        rooms
            .findById(request.roomId())
            .orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found"));
    if (!room.getHotel().getId().equals(request.hotelId())) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "HOTEL_MISMATCH", "Room belongs to a different hotel");
    }
    if (!room.isAvailable()
        || reservations.overlaps(room.getId(), request.startDate(), request.endDate())) {
      throw conflict("NO_AVAILABILITY", "Room is unavailable for the requested dates");
    }
    return room;
  }

  private Hotel lockHotel(Long id) {
    return hotels
        .lockById(id)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "HOTEL_NOT_FOUND", "Hotel not found"));
  }

  public static void validateDates(LocalDate start, LocalDate end) {
    if (start == null || end == null || !end.isAfter(start)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_DATES",
          "Dates are required and check-out must follow check-in");
    }
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
