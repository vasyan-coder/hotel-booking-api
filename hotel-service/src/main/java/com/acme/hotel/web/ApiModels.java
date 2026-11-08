package com.acme.hotel.web;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ApiModels {
  private ApiModels() {}

  public record HotelInput(
      @NotBlank @Size(max = 150) String name,
      @NotBlank @Size(max = 300) String address,
      @NotBlank @Size(max = 100) String city) {}

  public record HotelView(Long id, String name, String address, String city) {}

  public record RoomInput(
      @NotNull @Positive Long hotelId,
      @NotBlank @Size(max = 30) String number,
      boolean available,
      @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal pricePerNight,
      @Min(1) @Max(20) int capacity) {}

  public record RoomView(
      Long id,
      Long hotelId,
      String number,
      boolean available,
      long timesBooked,
      BigDecimal pricePerNight,
      int capacity) {}

  public record AllocationRequest(
      @NotNull UUID bookingId,
      @NotNull @Positive Long hotelId,
      @Positive Long roomId,
      boolean autoSelect,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate) {}

  public record AllocationResponse(Long roomId) {}

  public record ReleaseRequest(
      @NotNull UUID bookingId, @NotNull @Positive Long hotelId, @Positive Long roomId) {}

  public record RoomStatistics(
      Long roomId,
      String number,
      boolean available,
      long timesBooked,
      long bookedNights,
      double occupancyPercent) {}

  public record HotelStatistics(
      Long hotelId,
      LocalDate startDate,
      LocalDate endDate,
      long totalRooms,
      long operationalRooms,
      long totalBookings,
      long bookedNights,
      long roomNights,
      double occupancyPercent,
      List<RoomStatistics> rooms) {}
}
