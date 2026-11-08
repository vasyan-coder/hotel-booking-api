package com.acme.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "bookings",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_booking_request",
            columnNames = {"user_id", "request_id"}),
    indexes = {
      @Index(name = "ix_booking_owner_created", columnList = "user_id,created_at"),
      @Index(name = "ix_booking_recovery", columnList = "status,created_at"),
      @Index(name = "ix_booking_compensation", columnList = "compensation_required")
    })
public class Booking {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "request_id", nullable = false)
  private UUID requestId;

  @Column(name = "hotel_id", nullable = false)
  private Long hotelId;

  @Column(name = "requested_room_id")
  private Long requestedRoomId;

  @Column(name = "room_id")
  private Long roomId;

  @Column(name = "auto_select", nullable = false)
  private boolean autoSelect;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private BookingStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "compensation_required", nullable = false)
  private boolean compensationRequired;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Version private long version;

  protected Booking() {}

  public Booking(
      User user,
      UUID requestId,
      Long hotelId,
      Long roomId,
      boolean autoSelect,
      LocalDate startDate,
      LocalDate endDate) {
    id = UUID.randomUUID();
    this.user = user;
    this.requestId = requestId;
    this.hotelId = hotelId;
    requestedRoomId = autoSelect ? null : roomId;
    this.autoSelect = autoSelect;
    this.startDate = startDate;
    this.endDate = endDate;
    status = BookingStatus.PENDING;
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public UUID getRequestId() {
    return requestId;
  }

  public Long getHotelId() {
    return hotelId;
  }

  public Long getRequestedRoomId() {
    return requestedRoomId;
  }

  public Long getRoomId() {
    return roomId;
  }

  public boolean isAutoSelect() {
    return autoSelect;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public BookingStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public boolean isCompensationRequired() {
    return compensationRequired;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void confirm(Long roomId) {
    this.roomId = roomId;
    status = BookingStatus.CONFIRMED;
    updatedAt = Instant.now();
  }

  public void cancel(String reason) {
    status = BookingStatus.CANCELLED;
    failureCode = reason;
    compensationRequired = true;
    updatedAt = Instant.now();
  }

  public void compensationCompleted() {
    compensationRequired = false;
    updatedAt = Instant.now();
  }
}
