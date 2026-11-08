package com.acme.hotel.web;

import com.acme.hotel.service.*;
import com.acme.hotel.web.ApiModels.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hotels")
public class HotelController {
  private final CatalogService catalog;
  private final StatisticsService statistics;

  public HotelController(CatalogService catalog, StatisticsService statistics) {
    this.catalog = catalog;
    this.statistics = statistics;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  public Page<HotelView> list(
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String city,
      @PageableDefault(size = 20, sort = "id") Pageable pageable) {
    return catalog.hotels(name, city, pageable);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
  public HotelView get(@PathVariable Long id) {
    return catalog.hotel(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public HotelView create(@Valid @RequestBody HotelInput request) {
    return catalog.createHotel(request);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public HotelView update(@PathVariable Long id, @Valid @RequestBody HotelInput request) {
    return catalog.updateHotel(id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void delete(@PathVariable Long id) {
    catalog.deleteHotel(id);
  }

  @GetMapping("/{id}/statistics")
  @PreAuthorize("hasRole('ADMIN')")
  public HotelStatistics statistics(
      @PathVariable Long id, @RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
    return statistics.statistics(id, startDate, endDate);
  }
}
