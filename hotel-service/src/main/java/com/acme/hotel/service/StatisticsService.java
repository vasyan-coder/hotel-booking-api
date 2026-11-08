package com.acme.hotel.service;

import com.acme.common.ApiException;
import com.acme.hotel.domain.*;
import com.acme.hotel.repository.*;
import com.acme.hotel.web.ApiModels.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StatisticsService {
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final ReservationRepository reservations;

  public StatisticsService(
      HotelRepository hotels, RoomRepository rooms, ReservationRepository reservations) {
    this.hotels = hotels;
    this.rooms = rooms;
    this.reservations = reservations;
  }

  public HotelStatistics statistics(Long hotelId, LocalDate start, LocalDate end) {
    SchedulingService.validateDates(start, end);
    if (!hotels.existsById(hotelId)) {
      throw new ApiException(HttpStatus.NOT_FOUND, "HOTEL_NOT_FOUND", "Hotel not found");
    }
    long days = ChronoUnit.DAYS.between(start, end);
    List<Room> inventory = rooms.findByHotelIdOrderByIdAsc(hotelId);
    Map<Long, Long> nights = new HashMap<>();
    for (Reservation reservation : reservations.findOverlapping(hotelId, start, end)) {
      LocalDate from =
          reservation.getStartDate().isBefore(start) ? start : reservation.getStartDate();
      LocalDate to = reservation.getEndDate().isAfter(end) ? end : reservation.getEndDate();
      nights.merge(reservation.getRoom().getId(), ChronoUnit.DAYS.between(from, to), Long::sum);
    }
    List<RoomStatistics> perRoom =
        inventory.stream()
            .map(
                room -> {
                  long booked = nights.getOrDefault(room.getId(), 0L);
                  return new RoomStatistics(
                      room.getId(),
                      room.getNumber(),
                      room.isAvailable(),
                      room.getTimesBooked(),
                      booked,
                      percent(booked, days));
                })
            .toList();
    long booked = nights.values().stream().mapToLong(Long::longValue).sum();
    long capacity = days * inventory.size();
    return new HotelStatistics(
        hotelId,
        start,
        end,
        inventory.size(),
        inventory.stream().filter(Room::isAvailable).count(),
        inventory.stream().mapToLong(Room::getTimesBooked).sum(),
        booked,
        capacity,
        percent(booked, capacity),
        perRoom);
  }

  private static double percent(long numerator, long denominator) {
    return denominator == 0 ? 0 : Math.round(10000.0 * numerator / denominator) / 100.0;
  }
}
