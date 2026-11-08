package com.acme.hotel.repository;

import com.acme.hotel.domain.Reservation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
  @Query(
      "select count(r) > 0 from Reservation r where r.room.id = :roomId and r.status = com.acme.hotel.domain.Reservation.Status.ACTIVE and r.startDate < :endDate and r.endDate > :startDate")
  boolean overlaps(
      @Param("roomId") Long roomId,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate);

  @Query(
      "select r from Reservation r join fetch r.room where r.hotel.id = :hotelId and r.status = com.acme.hotel.domain.Reservation.Status.ACTIVE and r.startDate < :endDate and r.endDate > :startDate")
  List<Reservation> findOverlapping(
      @Param("hotelId") Long hotelId,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate);

  boolean existsByHotelId(Long hotelId);

  boolean existsByRoomId(Long roomId);
}
