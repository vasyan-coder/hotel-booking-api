package com.acme.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.acme.booking.integration.*;
import com.acme.booking.security.TokenService;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RestHotelClientTest {
  private HttpServer server;
  private ExecutorService executor;
  private RestHotelClient client;
  private final AtomicInteger attempts = new AtomicInteger();
  private final AtomicInteger releases = new AtomicInteger();
  private final AtomicReference<String> trace = new AtomicReference<>();
  private volatile int failureStatus;
  private volatile int failuresRemaining;
  private volatile long delayMillis;
  private volatile long bodyDelayMillis;
  private volatile int releaseStatus = 204;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/api/rooms/allocate",
        exchange -> {
          attempts.incrementAndGet();
          trace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
          }
          if (failuresRemaining-- > 0) {
            respond(exchange, failureStatus, "{}");
          } else if (bodyDelayMillis > 0) {
            byte[] body = "{\"roomId\":10}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try {
              Thread.sleep(bodyDelayMillis);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
            try (exchange) {
              exchange.getResponseBody().write(body);
            }
          } else {
            respond(exchange, 200, "{\"roomId\":10}");
          }
        });
    server.createContext(
        "/api/rooms/release",
        exchange -> {
          releases.incrementAndGet();
          respond(exchange, releaseStatus, "{}");
        });
    server.start();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(100);
    factory.setReadTimeout(100);
    TokenService tokens = mock(TokenService.class);
    when(tokens.internal()).thenReturn("internal-token");
    client =
        new RestHotelClient(
            RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory)
                .build(),
            tokens,
            3,
            1);
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
    server.stop(0);
    executor.shutdownNow();
  }

  @Test
  void retriesTransientFailuresAndForwardsTrace() {
    failureStatus = 503;
    failuresRemaining = 2;
    MDC.put("traceId", "trace-123");
    assertThat(client.allocate(allocation())).isEqualTo(10L);
    assertThat(attempts).hasValue(3);
    assertThat(trace.get()).isEqualTo("trace-123");
  }

  @Test
  void conflictIsNotRetried() {
    failureStatus = 409;
    failuresRemaining = 100;
    assertThatThrownBy(() -> client.allocate(allocation()))
        .isInstanceOfSatisfying(
            HotelCallException.class,
            exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    assertThat(attempts).hasValue(1);
  }

  @Test
  void timeoutsHaveBoundedRetries() {
    delayMillis = 400;
    long start = System.nanoTime();
    assertThatThrownBy(() -> client.allocate(allocation()))
        .isInstanceOfSatisfying(
            HotelCallException.class,
            exception ->
                assertThat(exception)
                    .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class));
    assertThat(attempts).hasValue(3);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
  }

  @Test
  void timeoutWhileReadingBodyIsRetried() {
    bodyDelayMillis = 400;
    assertThatThrownBy(() -> client.allocate(allocation())).isInstanceOf(HotelCallException.class);
    assertThat(attempts).hasValue(3);
  }

  @Test
  void openAllocationCircuitDoesNotBlockCompensation() {
    failureStatus = 503;
    failuresRemaining = 100;
    for (int i = 0; i < 6; i++) {
      assertThatThrownBy(() -> client.allocate(allocation()))
          .isInstanceOf(HotelCallException.class);
    }
    assertThat(attempts).hasValue(15);
    client.release(new HotelClient.Release(UUID.randomUUID(), 1L, null));
    assertThat(releases).hasValue(1);
  }

  @Test
  void releaseOfNonexistentHotelCompletesWithoutRetry() {
    releaseStatus = 404;
    client.release(new HotelClient.Release(UUID.randomUUID(), 123L, null));
    assertThat(releases).hasValue(1);
  }

  private HotelClient.Allocation allocation() {
    return new HotelClient.Allocation(
        UUID.randomUUID(),
        1L,
        10L,
        false,
        LocalDate.now().plusDays(1),
        LocalDate.now().plusDays(2));
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    try (exchange) {
      if (status == 204) {
        exchange.sendResponseHeaders(status, -1);
        return;
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }
}
