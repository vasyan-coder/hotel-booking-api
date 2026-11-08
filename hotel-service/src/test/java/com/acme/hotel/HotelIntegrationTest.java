package com.acme.hotel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.acme.common.ApiException;
import com.acme.hotel.repository.*;
import com.acme.hotel.service.*;
import com.acme.hotel.web.ApiModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "app.seed-data=false",
      "security.jwt-secret=test-secret-at-least-32-characters-long",
      "logging.structured.format.console="
    })
@AutoConfigureMockMvc
class HotelIntegrationTest {
  @Autowired CatalogService catalog;
  @Autowired SchedulingService scheduling;
  @Autowired StatisticsService statistics;
  @Autowired HotelRepository hotels;
  @Autowired RoomRepository rooms;
  @Autowired ReservationRepository reservations;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  Long hotelId;
  List<Long> roomIds;
  final LocalDate start = LocalDate.now().plusDays(10);
  final LocalDate end = start.plusDays(3);

  @BeforeEach
  void setUp() {
    reservations.deleteAll();
    rooms.deleteAll();
    hotels.deleteAll();
    hotelId = catalog.createHotel(new HotelInput("Test hotel", "Main street 1", "Moscow")).id();
    roomIds = new ArrayList<>();
    for (int number = 1; number <= 3; number++) {
      roomIds.add(
          catalog
              .createRoom(new RoomInput(hotelId, "10" + number, true, new BigDecimal("100.00"), 2))
              .id());
    }
  }

  @Test
  void adminAuthorizationPrecedesPayloadValidation() throws Exception {
    mvc.perform(
            post("/api/hotels")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void replayStillSucceedsAfterCheckInDateHasPassed() {
    UUID bookingId = UUID.randomUUID();
    var room = rooms.findById(roomIds.get(0)).orElseThrow();
    room.recordBooking();
    rooms.saveAndFlush(room);
    LocalDate pastStart = LocalDate.now().minusDays(1);
    LocalDate pastEnd = LocalDate.now().plusDays(1);
    reservations.saveAndFlush(
        new com.acme.hotel.domain.Reservation(
            bookingId, hotels.findById(hotelId).orElseThrow(), room, pastStart, pastEnd, false));
    var replay =
        scheduling.allocate(
            new AllocationRequest(bookingId, hotelId, room.getId(), false, pastStart, pastEnd));
    assertThat(replay.roomId()).isEqualTo(room.getId());
    assertThat(catalog.room(room.getId()).timesBooked()).isEqualTo(1);
  }

  @Test
  void parallelAllocationsNeverOverbookAndUseEveryRoom() throws Exception {
    var results =
        concurrently(
            12,
            ignored -> {
              try {
                return scheduling.allocate(autoRequest(UUID.randomUUID())).roomId();
              } catch (ApiException exception) {
                assertThat(exception.getStatus().value()).isEqualTo(409);
                return null;
              }
            });
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .containsExactlyInAnyOrderElementsOf(roomIds);
    assertThat(reservations.count()).isEqualTo(3);
    assertThat(rooms.findAll()).allSatisfy(room -> assertThat(room.getTimesBooked()).isEqualTo(1));
  }

  @Test
  void concurrentReplayUsesOneReservationAndOneCounterIncrement() throws Exception {
    UUID bookingId = UUID.randomUUID();
    List<Long> results =
        concurrently(8, ignored -> scheduling.allocate(autoRequest(bookingId)).roomId());
    assertThat(new HashSet<>(results)).containsExactly(roomIds.get(0));
    assertThat(reservations.count()).isEqualTo(1);
    assertThat(catalog.room(roomIds.get(0)).timesBooked()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                scheduling.allocate(
                    new AllocationRequest(bookingId, hotelId, null, true, start, end.plusDays(1))))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void leastUsedSelectionRemainsBalancedOverThirtyReservations() {
    for (int index = 0; index < 30; index++) {
      UUID bookingId = UUID.randomUUID();
      Long roomId = scheduling.allocate(autoRequest(bookingId)).roomId();
      scheduling.release(new ReleaseRequest(bookingId, hotelId, roomId));
    }
    assertThat(rooms.findAll()).allSatisfy(room -> assertThat(room.getTimesBooked()).isEqualTo(10));
  }

  @Test
  void adjacentDateIntervalsDoNotOverlap() {
    Long roomId = roomIds.get(0);
    scheduling.allocate(
        new AllocationRequest(UUID.randomUUID(), hotelId, roomId, false, start, end));
    scheduling.allocate(
        new AllocationRequest(UUID.randomUUID(), hotelId, roomId, false, end, end.plusDays(2)));
    assertThatThrownBy(
            () ->
                scheduling.allocate(
                    new AllocationRequest(
                        UUID.randomUUID(),
                        hotelId,
                        roomId,
                        false,
                        start.plusDays(1),
                        end.plusDays(1))))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(409));
    assertThat(catalog.room(roomId).timesBooked()).isEqualTo(2);
  }

  @Test
  void compensationBeforeConfirmationCreatesTerminalTombstone() {
    UUID bookingId = UUID.randomUUID();
    scheduling.release(new ReleaseRequest(bookingId, hotelId, null));
    scheduling.release(new ReleaseRequest(bookingId, hotelId, null));
    assertThatThrownBy(() -> scheduling.allocate(autoRequest(bookingId)))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("RESERVATION_RELEASED"));
    assertThat(reservations.count()).isEqualTo(1);
    assertThat(rooms.findAll()).allSatisfy(room -> assertThat(room.getTimesBooked()).isZero());
  }

  @Test
  void releaseFreesRoomButDoesNotEraseHistoricalCount() {
    UUID bookingId = UUID.randomUUID();
    Long roomId = scheduling.allocate(autoRequest(bookingId)).roomId();
    scheduling.release(new ReleaseRequest(bookingId, hotelId, roomId));
    scheduling.release(new ReleaseRequest(bookingId, hotelId, roomId));
    assertThatThrownBy(() -> scheduling.allocate(autoRequest(bookingId)))
        .isInstanceOf(ApiException.class);
    scheduling.allocate(
        new AllocationRequest(UUID.randomUUID(), hotelId, roomId, false, start, end));
    assertThat(catalog.room(roomId).timesBooked()).isEqualTo(2);
  }

  @Test
  void operationalAvailabilityAndDateValidationAreIndependent() {
    Long roomId = roomIds.get(0);
    catalog.updateRoom(roomId, new RoomInput(hotelId, "101", false, new BigDecimal("100.00"), 2));
    assertThatThrownBy(
            () ->
                scheduling.allocate(
                    new AllocationRequest(UUID.randomUUID(), hotelId, roomId, false, start, end)))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABILITY"));
    assertThatThrownBy(
            () ->
                scheduling.allocate(
                    new AllocationRequest(
                        UUID.randomUUID(), hotelId, roomIds.get(1), false, end, start)))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(400));
    assertThatThrownBy(
            () ->
                scheduling.allocate(
                    new AllocationRequest(
                        UUID.randomUUID(),
                        hotelId,
                        roomIds.get(1),
                        false,
                        LocalDate.now().minusDays(1),
                        end)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void statisticsClipIntervalsAndExcludeCompensatedReservations() {
    UUID first = UUID.randomUUID();
    scheduling.allocate(new AllocationRequest(first, hotelId, roomIds.get(0), false, start, end));
    UUID second = UUID.randomUUID();
    Long releasedRoom = scheduling.allocate(autoRequest(second)).roomId();
    scheduling.release(new ReleaseRequest(second, hotelId, releasedRoom));
    HotelStatistics result = statistics.statistics(hotelId, start.plusDays(1), end.plusDays(1));
    assertThat(result.bookedNights()).isEqualTo(2);
    assertThat(result.roomNights()).isEqualTo(9);
    assertThat(result.occupancyPercent()).isEqualTo(22.22);
    assertThat(result.totalBookings()).isEqualTo(2);
  }

  @Test
  void recommendationSortsByUsageThenIdAndExcludesOverlap() throws Exception {
    UUID used = UUID.randomUUID();
    scheduling.allocate(new AllocationRequest(used, hotelId, roomIds.get(0), false, start, end));
    scheduling.release(new ReleaseRequest(used, hotelId, roomIds.get(0)));
    scheduling.allocate(
        new AllocationRequest(UUID.randomUUID(), hotelId, roomIds.get(1), false, start, end));
    mvc.perform(
            get("/api/rooms/recommend")
                .param("hotelId", hotelId.toString())
                .param("startDate", start.toString())
                .param("endDate", end.toString())
                .with(user("USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].id").value(roomIds.get(2)))
        .andExpect(jsonPath("$.content[1].id").value(roomIds.get(0)));
  }

  @Test
  void securityEnforcesAuthenticationAndAdministrativeBoundaries() throws Exception {
    mvc.perform(get("/api/hotels"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").exists());
    mvc.perform(get("/api/hotels").header("Authorization", "Bearer invalid.jwt.token"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/hotels").with(user("USER"))).andExpect(status().isOk());
    mvc.perform(
            post("/api/hotels")
                .with(user("USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new HotelInput("New", "Street", "City"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/hotels/{id}/statistics", hotelId)
                .with(user("USER"))
                .param("startDate", start.toString())
                .param("endDate", end.toString()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/rooms/allocate")
                .with(user("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(autoRequest(UUID.randomUUID()))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCrudAndInvalidInputsReturnExpectedStatuses() throws Exception {
    var response =
        mvc.perform(
                post("/api/hotels")
                    .with(user("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(new HotelInput("New", "Street", "City"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long created = mapper.readTree(response).get("id").asLong();
    mvc.perform(
            put("/api/hotels/{id}", created)
                .with(user("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new HotelInput("Updated", "Street", "City"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Updated"));
    mvc.perform(delete("/api/hotels/{id}", created).with(user("ADMIN")))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/hotels/{id}", created).with(user("USER")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/hotels")
                .with(user("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"address\":\"Street\",\"city\":\"City\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").exists());
    mvc.perform(get("/api/rooms").with(user("USER")).param("startDate", start.toString()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/rooms").with(user("USER")).param("sort", "missing,asc"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void internalEndpointsReserveAndReleaseOnlyWithInternalRole() throws Exception {
    UUID bookingId = UUID.randomUUID();
    var request = new AllocationRequest(bookingId, hotelId, roomIds.get(0), false, start, end);
    mvc.perform(
            post("/api/rooms/{id}/confirm-availability", roomIds.get(0))
                .with(user("INTERNAL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId").value(roomIds.get(0)));
    mvc.perform(
            post("/api/rooms/{id}/release", roomIds.get(0))
                .with(user("INTERNAL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new ReleaseRequest(bookingId, hotelId, roomIds.get(0)))))
        .andExpect(status().isNoContent());
    mvc.perform(
            post("/api/rooms/allocate")
                .with(user("INTERNAL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESERVATION_RELEASED"));
  }

  @Test
  void roomCrudProtectsHistoryAndValidatesForeignKeys() throws Exception {
    var body =
        mapper.writeValueAsString(new RoomInput(hotelId, "401", true, new BigDecimal("300.00"), 4));
    String response =
        mvc.perform(
                post("/api/rooms")
                    .with(user("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long created = mapper.readTree(response).get("id").asLong();
    mvc.perform(
            post("/api/rooms")
                .with(user("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_ROOM"));
    mvc.perform(
            put("/api/rooms/{id}", created)
                .with(user("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new RoomInput(hotelId, "402", false, new BigDecimal("400.00"), 4))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));
    mvc.perform(get("/api/rooms/{id}", created).with(user("USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.number").value("402"));
    mvc.perform(delete("/api/rooms/{id}", created).with(user("ADMIN")))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/rooms/{id}", created).with(user("USER")))
        .andExpect(status().isNotFound());
    scheduling.allocate(
        new AllocationRequest(UUID.randomUUID(), hotelId, roomIds.get(0), false, start, end));
    mvc.perform(delete("/api/rooms/{id}", roomIds.get(0)).with(user("ADMIN")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_HAS_HISTORY"));
    mvc.perform(delete("/api/hotels/{id}", hotelId).with(user("ADMIN")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOTEL_HAS_HISTORY"));
  }

  @Test
  void listFiltersPaginatesAndProvidesAdministrativeStatistics() throws Exception {
    mvc.perform(
            get("/api/hotels")
                .with(user("USER"))
                .param("city", "Moscow")
                .param("name", "Test")
                .param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            get("/api/rooms")
                .with(user("USER"))
                .param("hotelId", hotelId.toString())
                .param("minPrice", "50")
                .param("maxPrice", "150")
                .param("minCapacity", "2")
                .param("size", "2")
                .param("sort", "id,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].id").value(roomIds.get(2)));
    mvc.perform(
            get("/api/hotels/{id}/statistics", hotelId)
                .with(user("ADMIN"))
                .param("startDate", start.toString())
                .param("endDate", end.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomNights").value(9))
        .andExpect(jsonPath("$.bookedNights").value(0));
  }

  private AllocationRequest autoRequest(UUID bookingId) {
    return new AllocationRequest(bookingId, hotelId, null, true, start, end);
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor user(
      String role) {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private static <T> List<T> concurrently(int workers, java.util.function.IntFunction<T> task)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int index = 0; index < workers; index++) {
        int worker = index;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return task.apply(worker);
                }));
      }
      start.countDown();
      List<T> result = new ArrayList<>();
      for (Future<T> future : futures) result.add(future.get(20, TimeUnit.SECONDS));
      return result;
    } finally {
      executor.shutdownNow();
    }
  }
}
