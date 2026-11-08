package com.acme.booking.integration;

import com.acme.booking.api.ApiModels.BookingView;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

public interface HotelClient {
  Long allocate(Allocation request);

  void release(Release request);

  JsonNode recommendations(
      Long hotelId, LocalDate startDate, LocalDate endDate, int page, int size, String bearerToken);

  record Allocation(
      java.util.UUID bookingId,
      Long hotelId,
      Long roomId,
      boolean autoSelect,
      LocalDate startDate,
      LocalDate endDate) {}

  record Release(java.util.UUID bookingId, Long hotelId, Long roomId) {
    public static Release from(BookingView booking) {
      return new Release(booking.id(), booking.hotelId(), booking.roomId());
    }
  }

  record AllocationResponse(Long roomId) {}
}
