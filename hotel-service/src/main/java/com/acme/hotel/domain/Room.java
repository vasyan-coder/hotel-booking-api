package com.acme.hotel.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(
    name = "rooms",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_room_number",
            columnNames = {"hotel_id", "number"}),
    indexes =
        @Index(name = "idx_rooms_scheduling", columnList = "hotel_id,available,times_booked,id"))
public class Room {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hotel_id", nullable = false)
  private Hotel hotel;

  @Column(nullable = false, length = 30)
  private String number;

  @Column(nullable = false)
  private boolean available;

  @Column(name = "times_booked", nullable = false)
  private long timesBooked;

  @Column(name = "price_per_night", nullable = false, precision = 12, scale = 2)
  private BigDecimal pricePerNight;

  @Column(nullable = false)
  private int capacity;

  protected Room() {}

  public Room(
      Hotel hotel, String number, boolean available, BigDecimal pricePerNight, int capacity) {
    this.hotel = hotel;
    update(number, available, pricePerNight, capacity);
  }

  public void update(String number, boolean available, BigDecimal pricePerNight, int capacity) {
    this.number = number;
    this.available = available;
    this.pricePerNight = pricePerNight;
    this.capacity = capacity;
  }

  public void recordBooking() {
    timesBooked++;
  }

  public Long getId() {
    return id;
  }

  public Hotel getHotel() {
    return hotel;
  }

  public String getNumber() {
    return number;
  }

  public boolean isAvailable() {
    return available;
  }

  public long getTimesBooked() {
    return timesBooked;
  }

  public BigDecimal getPricePerNight() {
    return pricePerNight;
  }

  public int getCapacity() {
    return capacity;
  }
}
