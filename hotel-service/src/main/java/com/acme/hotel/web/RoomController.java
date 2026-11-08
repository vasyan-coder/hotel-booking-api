package com.acme.hotel.web;

import com.acme.hotel.service.CatalogService;
import com.acme.hotel.web.ApiModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@Validated
public class RoomController {
  private final CatalogService catalog;

  public RoomController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  public Page<RoomView> list(
      @RequestParam(required = false) @Positive Long hotelId,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam(required = false) LocalDate endDate,
      @RequestParam(defaultValue = "true") Boolean available,
      @RequestParam(required = false) @Min(1) Integer minCapacity,
      @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
      @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
      @PageableDefault(size = 20, sort = "id") Pageable page) {
    return catalog.rooms(
        hotelId, startDate, endDate, available, minCapacity, minPrice, maxPrice, page, false);
  }

  @GetMapping("/recommend")
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  public Page<RoomView> recommend(
      @RequestParam(required = false) @Positive Long hotelId,
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate,
      @RequestParam(required = false) @Min(1) Integer minCapacity,
      @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
      @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
      @PageableDefault(size = 20, sort = "id") Pageable page) {
    return catalog.rooms(
        hotelId, startDate, endDate, true, minCapacity, minPrice, maxPrice, page, true);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  public RoomView get(@PathVariable Long id) {
    return catalog.room(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public RoomView create(@Valid @RequestBody RoomInput request) {
    return catalog.createRoom(request);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public RoomView update(@PathVariable Long id, @Valid @RequestBody RoomInput request) {
    return catalog.updateRoom(id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void delete(@PathVariable Long id) {
    catalog.deleteRoom(id);
  }
}
