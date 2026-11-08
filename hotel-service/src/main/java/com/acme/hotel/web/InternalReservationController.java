package com.acme.hotel.web;

import com.acme.common.ApiException;
import com.acme.hotel.service.SchedulingService;
import com.acme.hotel.web.ApiModels.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@PreAuthorize("hasRole('INTERNAL')")
public class InternalReservationController {
  private final SchedulingService scheduling;

  public InternalReservationController(SchedulingService scheduling) {
    this.scheduling = scheduling;
  }

  @PostMapping("/allocate")
  public AllocationResponse allocate(@Valid @RequestBody AllocationRequest request) {
    return scheduling.allocate(request);
  }

  @PostMapping("/{id}/confirm-availability")
  public AllocationResponse confirm(
      @PathVariable Long id, @Valid @RequestBody AllocationRequest request) {
    if (request.autoSelect() || (request.roomId() != null && !id.equals(request.roomId()))) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "ROOM_MISMATCH", "Path room must match explicit room selection");
    }
    return scheduling.allocate(
        new AllocationRequest(
            request.bookingId(),
            request.hotelId(),
            id,
            false,
            request.startDate(),
            request.endDate()));
  }

  @PostMapping("/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@Valid @RequestBody ReleaseRequest request) {
    scheduling.release(request);
  }

  @PostMapping("/{id}/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void releaseRoom(@PathVariable Long id, @Valid @RequestBody ReleaseRequest request) {
    if (request.roomId() != null && !id.equals(request.roomId())) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "ROOM_MISMATCH", "Path room must match request room");
    }
    scheduling.release(new ReleaseRequest(request.bookingId(), request.hotelId(), id));
  }
}
