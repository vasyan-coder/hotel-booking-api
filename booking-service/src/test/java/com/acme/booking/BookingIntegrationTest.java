package com.acme.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.acme.booking.api.ApiModels.*;
import com.acme.booking.domain.*;
import com.acme.booking.integration.*;
import com.acme.booking.persistence.*;
import com.acme.booking.security.*;
import com.acme.booking.service.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "security.jwt-secret=01234567890123456789012345678901",
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "hotel.discovery=false",
      "app.seed-data=false",
      "app.recovery.enabled=false",
      "spring.datasource.url=jdbc:h2:mem:booking-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
    })
@AutoConfigureMockMvc
class BookingIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired UserRepository users;
  @Autowired BookingRepository repository;
  @Autowired BookingService bookings;
  @Autowired BookingTransactions transactions;
  @Autowired TokenService tokens;
  @Autowired PasswordEncoder passwords;
  @MockitoBean HotelClient hotel;
  private User owner;
  private String ownerToken;
  private String adminToken;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    users.deleteAll();
    owner = users.save(new User("alice", passwords.encode("password123"), Role.USER));
    ownerToken = tokens.forUser(owner);
    adminToken =
        tokens.forUser(users.save(new User("admin", passwords.encode("password123"), Role.ADMIN)));
    when(hotel.allocate(any())).thenReturn(10L);
  }

  @Test
  void registerLoginAndPasswordProtection() throws Exception {
    String registration = "{\"username\":\"new-user\",\"password\":\"password123\"}";
    mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registration))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.role").value("USER"))
        .andExpect(jsonPath("$.accessToken").isString())
        .andExpect(jsonPath("$.expiresIn").value(3600))
        .andExpect(jsonPath("$.user.password").doesNotExist());
    assertThat(users.findByUsername("new-user").orElseThrow().getPassword()).startsWith("$2a$");
    mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registration))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(registration))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"new-user\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authenticationAndRolesHaveCorrectStatuses() throws Exception {
    mvc.perform(get("/api/bookings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.traceId").isString());
    mvc.perform(get("/api/bookings").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/users").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  @Test
  void confirmsBookingAndRepeatedDeliveryReturnsSameBooking() throws Exception {
    CreateBookingRequest request = request(UUID.randomUUID());
    JsonNode created = create(request, 201);
    JsonNode repeated = create(request, 200);
    assertThat(created.get("id").asText()).isEqualTo(repeated.get("id").asText());
    assertThat(created.get("status").asText()).isEqualTo("CONFIRMED");
    assertThat(repository.count()).isEqualTo(1);
    verify(hotel, times(1)).allocate(any());
  }

  @Test
  void requestIdCannotBeReusedWithDifferentPayload() throws Exception {
    UUID requestId = UUID.randomUUID();
    CreateBookingRequest initial = request(requestId);
    create(initial, 201);
    CreateBookingRequest changed =
        new CreateBookingRequest(requestId, 2L, 10L, false, initial.startDate(), initial.endDate());
    create(changed, 409);
    verify(hotel, times(1)).allocate(any());
  }

  @Test
  void automaticSelectionIgnoresRequestedRoom() throws Exception {
    CreateBookingRequest base = request(UUID.randomUUID());
    CreateBookingRequest first =
        new CreateBookingRequest(
            base.requestId(), 1L, -999L, true, base.startDate(), base.endDate());
    CreateBookingRequest repeat =
        new CreateBookingRequest(
            base.requestId(), 1L, null, true, base.startDate(), base.endDate());
    create(first, 201);
    create(repeat, 200);
    verify(hotel)
        .allocate(argThat(allocation -> allocation.roomId() == null && allocation.autoSelect()));
  }

  @Test
  void rejectsInvalidDatesAndMissingRoom() throws Exception {
    LocalDate today = LocalDate.now();
    create(new CreateBookingRequest(UUID.randomUUID(), 1L, 10L, false, today, today), 400);
    create(
        new CreateBookingRequest(UUID.randomUUID(), 1L, 10L, false, today.minusDays(1), today),
        400);
    create(
        new CreateBookingRequest(UUID.randomUUID(), 1L, null, false, today, today.plusDays(1)),
        400);
    mvc.perform(
            post("/api/bookings")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(hotel);
  }

  @Test
  void historyIsPaginatedAndOnlyOwnerCanReadOrCancel() throws Exception {
    JsonNode booking = create(request(UUID.randomUUID()), 201);
    User stranger = users.save(new User("bob", passwords.encode("password123"), Role.USER));
    String strangerToken = tokens.forUser(stranger);
    mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + strangerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/bookings/" + booking.get("id").asText())
                .header("Authorization", "Bearer " + strangerToken))
        .andExpect(status().isNotFound());
    mvc.perform(
            delete("/api/bookings/" + booking.get("id").asText())
                .header("Authorization", "Bearer " + strangerToken))
        .andExpect(status().isNotFound());
    create(request(UUID.randomUUID()), 201);
    mvc.perform(get("/api/bookings?size=1&page=0").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(1));
  }

  @Test
  void cancellationIsIdempotentAndReleasesAllocation() throws Exception {
    String id = create(request(UUID.randomUUID()), 201).get("id").asText();
    for (int i = 0; i < 2; i++) {
      mvc.perform(delete("/api/bookings/" + id).header("Authorization", "Bearer " + ownerToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("CANCELLED"))
          .andExpect(jsonPath("$.compensationRequired").value(false));
    }
    verify(hotel, times(1)).release(any());
  }

  @Test
  void roomConflictCancelsAndCompensatesWithSameBookingId() throws Exception {
    when(hotel.allocate(any()))
        .thenThrow(new HotelCallException(HttpStatus.CONFLICT, "Occupied", null));
    create(request(UUID.randomUUID()), 409);
    Booking result = repository.findAll().get(0);
    assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(result.isCompensationRequired()).isFalse();
    verify(hotel).release(argThat(release -> release.bookingId().equals(result.getId())));
  }

  @Test
  void missingHotelIsReportedAsNotFound() throws Exception {
    when(hotel.allocate(any()))
        .thenThrow(new HotelCallException(HttpStatus.NOT_FOUND, "Missing", null));
    create(request(UUID.randomUUID()), 404);
    assertThat(repository.findAll().get(0).getStatus()).isEqualTo(BookingStatus.CANCELLED);
  }

  @Test
  void failedCompensationIsPersistedAndRetried() throws Exception {
    when(hotel.allocate(any()))
        .thenThrow(new HotelCallException(HttpStatus.SERVICE_UNAVAILABLE, "Timeout", null));
    doThrow(new HotelCallException(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null))
        .when(hotel)
        .release(any());
    create(request(UUID.randomUUID()), 503);
    Booking failed = repository.findAll().get(0);
    assertThat(failed.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(failed.isCompensationRequired()).isTrue();
    doNothing().when(hotel).release(any());
    new BookingRecovery(transactions, bookings, 30).recover();
    assertThat(repository.findById(failed.getId()).orElseThrow().isCompensationRequired())
        .isFalse();
  }

  @Test
  void stalePendingIsCancelledAndLateConfirmationCannotResurrectIt() {
    var reservation = transactions.reserve(owner.getId(), request(UUID.randomUUID()));
    BookingView cancelled =
        transactions
            .expirePending(reservation.booking().id(), Instant.now().plusSeconds(1))
            .orElseThrow();
    bookings.compensate(cancelled);
    BookingView late = transactions.confirm(cancelled.id(), 10L);
    assertThat(late.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(late.compensationRequired()).isFalse();
  }

  @Test
  void concurrentRepeatedDeliveryCreatesOnlyOneAllocation() throws Exception {
    CountDownLatch allocating = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger allocations = new AtomicInteger();
    when(hotel.allocate(any()))
        .thenAnswer(
            invocation -> {
              allocations.incrementAndGet();
              allocating.countDown();
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
              return 10L;
            });
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CreateBookingRequest request = request(UUID.randomUUID());
      Actor actor = new Actor(owner.getId(), false);
      Future<BookingService.Creation> leader = pool.submit(() -> bookings.create(actor, request));
      assertThat(allocating.await(10, TimeUnit.SECONDS)).isTrue();
      List<Future<BookingService.Creation>> repeats = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        repeats.add(pool.submit(() -> bookings.create(actor, request)));
      }
      Set<UUID> ids = new HashSet<>();
      for (Future<BookingService.Creation> repeat : repeats) {
        BookingService.Creation response = repeat.get(10, TimeUnit.SECONDS);
        assertThat(response.created()).isFalse();
        ids.add(response.booking().id());
      }
      release.countDown();
      ids.add(leader.get(10, TimeUnit.SECONDS).booking().id());
      assertThat(ids).hasSize(1);
      assertThat(repository.count()).isEqualTo(1);
      assertThat(allocations).hasValue(1);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void cancellationDuringAllocationCannotBecomeConfirmed() throws Exception {
    CountDownLatch allocating = new CountDownLatch(1);
    CountDownLatch complete = new CountDownLatch(1);
    when(hotel.allocate(any()))
        .thenAnswer(
            invocation -> {
              allocating.countDown();
              assertThat(complete.await(10, TimeUnit.SECONDS)).isTrue();
              return 10L;
            });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Actor actor = new Actor(owner.getId(), false);
      Future<BookingService.Creation> creating =
          pool.submit(() -> bookings.create(actor, request(UUID.randomUUID())));
      assertThat(allocating.await(10, TimeUnit.SECONDS)).isTrue();
      UUID id = repository.findAll().get(0).getId();
      bookings.cancel(id, actor);
      complete.countDown();
      assertThat(creating.get(10, TimeUnit.SECONDS).booking().status())
          .isEqualTo(BookingStatus.CANCELLED);
      assertThat(repository.findById(id).orElseThrow().getStatus())
          .isEqualTo(BookingStatus.CANCELLED);
    } finally {
      complete.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void userAdministrationProtectsPasswordsAndCancelsOnDelete() throws Exception {
    create(request(UUID.randomUUID()), 201);
    mvc.perform(
            patch("/api/users/" + owner.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"changed123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.password").doesNotExist());
    assertThat(
            passwords.matches(
                "changed123", users.findById(owner.getId()).orElseThrow().getPassword()))
        .isTrue();
    mvc.perform(
            delete("/api/users/" + owner.getId()).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());
    assertThat(repository.findAll().get(0).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"changed123\"}"))
        .andExpect(status().isUnauthorized());
  }

  private CreateBookingRequest request(UUID id) {
    return new CreateBookingRequest(
        id, 1L, 10L, false, LocalDate.now().plusDays(1), LocalDate.now().plusDays(3));
  }

  private JsonNode create(CreateBookingRequest request, int expectedStatus) throws Exception {
    String response =
        mvc.perform(
                post("/api/bookings")
                    .header("Authorization", "Bearer " + ownerToken)
                    .header("X-Trace-Id", "booking-integration")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().is(expectedStatus))
            .andExpect(header().string("X-Trace-Id", "booking-integration"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(response);
  }
}
