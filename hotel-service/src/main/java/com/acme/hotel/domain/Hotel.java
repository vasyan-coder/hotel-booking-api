package com.acme.hotel.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "hotels", indexes = @Index(name = "idx_hotels_city", columnList = "city"))
public class Hotel {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 150)
  private String name;

  @Column(nullable = false, length = 300)
  private String address;

  @Column(nullable = false, length = 100)
  private String city;

  protected Hotel() {}

  public Hotel(String name, String address, String city) {
    update(name, address, city);
  }

  public void update(String name, String address, String city) {
    this.name = name;
    this.address = address;
    this.city = city;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getAddress() {
    return address;
  }

  public String getCity() {
    return city;
  }
}
