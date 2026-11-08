package com.acme.hotel.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A local copy of the booked interval; RELEASED rows are terminal idempotency tombstones. */
@Entity
@Table(
    name = "room_reservations",
    indexes = {
      @Index(name = "idx_reservation_overlap", columnList = "room_id,status,start_date,end_date"),
      @Index(name = "idx_reservation_hotel", columnList = "hotel_id,status")
    })
public class Reservation {
  public enum Status {
    ACTIVE,
    RELEASED
  }

  @Id
  @Column(name = "booking_id")
  private UUID bookingId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hotel_id", nullable = false)
  private Hotel hotel;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id")
  private Room room;

  @Column(name = "start_date")
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(name = "auto_select", nullable = false)
  private boolean autoSelect;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Reservation() {}

  public Reservation(
      UUID bookingId,
      Hotel hotel,
      Room room,
      LocalDate startDate,
      LocalDate endDate,
      boolean autoSelect) {
    this.bookingId = bookingId;
    this.hotel = hotel;
    this.room = room;
    this.startDate = startDate;
    this.endDate = endDate;
    this.autoSelect = autoSelect;
    this.status = Status.ACTIVE;
    this.createdAt = Instant.now();
  }

  public static Reservation tombstone(UUID bookingId, Hotel hotel) {
    Reservation reservation = new Reservation(bookingId, hotel, null, null, null, false);
    reservation.release();
    return reservation;
  }

  public void release() {
    status = Status.RELEASED;
  }

  public UUID getBookingId() {
    return bookingId;
  }

  public Hotel getHotel() {
    return hotel;
  }

  public Room getRoom() {
    return room;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public boolean isAutoSelect() {
    return autoSelect;
  }

  public Status getStatus() {
    return status;
  }
}
