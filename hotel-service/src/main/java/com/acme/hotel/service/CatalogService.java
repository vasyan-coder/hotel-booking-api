package com.acme.hotel.service;

import com.acme.common.ApiException;
import com.acme.hotel.domain.*;
import com.acme.hotel.repository.*;
import com.acme.hotel.web.ApiModels.*;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final ReservationRepository reservations;

  public CatalogService(
      HotelRepository hotels, RoomRepository rooms, ReservationRepository reservations) {
    this.hotels = hotels;
    this.rooms = rooms;
    this.reservations = reservations;
  }

  public Page<HotelView> hotels(String name, String city, Pageable page) {
    validatePage(page, Set.of("id", "name", "city", "address"));
    Specification<Hotel> filter =
        (root, query, cb) -> {
          List<Predicate> conditions = new ArrayList<>();
          if (name != null && !name.isBlank()) {
            conditions.add(cb.like(cb.lower(root.get("name")), pattern(name), '\\'));
          }
          if (city != null && !city.isBlank()) {
            conditions.add(cb.equal(cb.lower(root.get("city")), city.toLowerCase(Locale.ROOT)));
          }
          return cb.and(conditions.toArray(Predicate[]::new));
        };
    return hotels.findAll(filter, page).map(CatalogService::view);
  }

  public HotelView hotel(Long id) {
    return view(findHotel(id));
  }

  public RoomView room(Long id) {
    return view(findRoom(id));
  }

  @Transactional
  public HotelView createHotel(HotelInput input) {
    return view(hotels.save(new Hotel(input.name(), input.address(), input.city())));
  }

  @Transactional
  public HotelView updateHotel(Long id, HotelInput input) {
    Hotel hotel = lockHotel(id);
    hotel.update(input.name(), input.address(), input.city());
    return view(hotel);
  }

  @Transactional
  public void deleteHotel(Long id) {
    Hotel hotel = lockHotel(id);
    if (reservations.existsByHotelId(id)) {
      throw conflict("HOTEL_HAS_HISTORY", "Hotel with reservation history cannot be deleted");
    }
    rooms.deleteAll(rooms.findByHotelIdOrderByIdAsc(id));
    hotels.delete(hotel);
  }

  @Transactional
  public RoomView createRoom(RoomInput input) {
    Hotel hotel = lockHotel(input.hotelId());
    if (rooms.existsByHotelIdAndNumber(input.hotelId(), input.number())) {
      throw conflict("DUPLICATE_ROOM", "Room number must be unique within the hotel");
    }
    return view(
        rooms.save(
            new Room(
                hotel,
                input.number(),
                input.available(),
                input.pricePerNight(),
                input.capacity())));
  }

  @Transactional
  public RoomView updateRoom(Long id, RoomInput input) {
    lockHotel(input.hotelId());
    Room room = findRoom(id);
    if (!room.getHotel().getId().equals(input.hotelId())) {
      throw conflict("HOTEL_IMMUTABLE", "A room cannot be moved to another hotel");
    }
    if (rooms.existsByHotelIdAndNumberAndIdNot(input.hotelId(), input.number(), id)) {
      throw conflict("DUPLICATE_ROOM", "Room number must be unique within the hotel");
    }
    room.update(input.number(), input.available(), input.pricePerNight(), input.capacity());
    return view(room);
  }

  @Transactional
  public void deleteRoom(Long id) {
    Long hotelId =
        rooms.findHotelId(id).orElseThrow(() -> notFound("ROOM_NOT_FOUND", "Room not found"));
    lockHotel(hotelId);
    Room room = findRoom(id);
    if (reservations.existsByRoomId(id)) {
      throw conflict(
          "ROOM_HAS_HISTORY",
          "Room with reservation history cannot be deleted; disable it instead");
    }
    rooms.delete(room);
  }

  public Page<RoomView> rooms(
      Long hotelId,
      LocalDate start,
      LocalDate end,
      Boolean available,
      Integer minCapacity,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      Pageable page,
      boolean recommend) {
    if (start != null || end != null) {
      SchedulingService.validateDates(start, end);
    }
    if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE", "minPrice must not exceed maxPrice");
    }
    validatePage(page, Set.of("id", "number", "timesBooked", "pricePerNight", "capacity"));
    Pageable effective =
        recommend
            ? PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("timesBooked", "id"))
            : page;
    Specification<Room> filter =
        (root, query, cb) -> {
          List<Predicate> conditions = new ArrayList<>();
          if (hotelId != null) conditions.add(cb.equal(root.get("hotel").get("id"), hotelId));
          conditions.add(cb.equal(root.get("available"), available == null || available));
          if (minCapacity != null) conditions.add(cb.ge(root.get("capacity"), minCapacity));
          if (minPrice != null) conditions.add(cb.ge(root.get("pricePerNight"), minPrice));
          if (maxPrice != null) conditions.add(cb.le(root.get("pricePerNight"), maxPrice));
          if (start != null) {
            var occupied = query.subquery(UUID.class);
            var reservation = occupied.from(Reservation.class);
            occupied
                .select(reservation.get("bookingId"))
                .where(
                    cb.equal(reservation.get("room").get("id"), root.get("id")),
                    cb.equal(reservation.get("status"), Reservation.Status.ACTIVE),
                    cb.lessThan(reservation.get("startDate"), end),
                    cb.greaterThan(reservation.get("endDate"), start));
            conditions.add(cb.not(cb.exists(occupied)));
          }
          return cb.and(conditions.toArray(Predicate[]::new));
        };
    return rooms.findAll(filter, effective).map(CatalogService::view);
  }

  private static void validatePage(Pageable page, Set<String> allowedSorts) {
    if (page.getPageSize() > 100 || page.getPageNumber() < 0) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page size must be between 1 and 100");
    }
    for (Sort.Order order : page.getSort()) {
      if (!allowedSorts.contains(order.getProperty())) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_SORT",
            "Unsupported sort property: " + order.getProperty());
      }
    }
  }

  private static String pattern(String value) {
    return "%"
        + value
            .toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        + "%";
  }

  private Hotel findHotel(Long id) {
    return hotels.findById(id).orElseThrow(() -> notFound("HOTEL_NOT_FOUND", "Hotel not found"));
  }

  private Hotel lockHotel(Long id) {
    return hotels.lockById(id).orElseThrow(() -> notFound("HOTEL_NOT_FOUND", "Hotel not found"));
  }

  private Room findRoom(Long id) {
    return rooms.findById(id).orElseThrow(() -> notFound("ROOM_NOT_FOUND", "Room not found"));
  }

  public static HotelView view(Hotel hotel) {
    return new HotelView(hotel.getId(), hotel.getName(), hotel.getAddress(), hotel.getCity());
  }

  public static RoomView view(Room room) {
    return new RoomView(
        room.getId(),
        room.getHotel().getId(),
        room.getNumber(),
        room.isAvailable(),
        room.getTimesBooked(),
        room.getPricePerNight(),
        room.getCapacity());
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  private static ApiException notFound(String code, String message) {
    return new ApiException(HttpStatus.NOT_FOUND, code, message);
  }
}
