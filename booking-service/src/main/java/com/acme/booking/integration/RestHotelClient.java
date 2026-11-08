package com.acme.booking.integration;

import com.acme.booking.security.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.*;
import java.time.*;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class RestHotelClient implements HotelClient {
  private final RestClient client;
  private final TokenService tokens;
  private final Retry retry;
  private final CircuitBreaker allocationCircuit;
  private final CircuitBreaker releaseCircuit;

  public RestHotelClient(
      RestClient client,
      TokenService tokens,
      @Value("${hotel.retry-attempts:3}") int attempts,
      @Value("${hotel.retry-delay-ms:100}") long delay) {
    this.client = client;
    this.tokens = tokens;
    retry =
        Retry.of(
            "hotel",
            RetryConfig.custom()
                .maxAttempts(attempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(delay, 2.0))
                .retryOnException(RestHotelClient::transientFailure)
                .build());
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .recordException(RestHotelClient::transientFailure)
            .build();
    allocationCircuit = CircuitBreaker.of("hotel-allocation", config);
    releaseCircuit = CircuitBreaker.of("hotel-compensation", config);
  }

  @Override
  public Long allocate(Allocation request) {
    return invoke(
        allocationCircuit,
        () -> {
          AllocationResponse response =
              client
                  .post()
                  .uri("/api/rooms/allocate")
                  .headers(this::internalHeaders)
                  .body(request)
                  .retrieve()
                  .body(AllocationResponse.class);
          if (response == null || response.roomId() == null) {
            throw new HotelCallException(
                HttpStatus.SERVICE_UNAVAILABLE, "Invalid Hotel response", null);
          }
          return response.roomId();
        });
  }

  @Override
  public void release(Release request) {
    invoke(
        releaseCircuit,
        () -> {
          try {
            client
                .post()
                .uri("/api/rooms/release")
                .headers(this::internalHeaders)
                .body(request)
                .retrieve()
                .toBodilessEntity();
          } catch (HttpClientErrorException.NotFound ignored) {
            // A deleted/nonexistent hotel cannot retain a room allocation.
          }
          return Boolean.TRUE;
        });
  }

  @Override
  public JsonNode recommendations(
      Long hotelId, LocalDate startDate, LocalDate endDate, int page, int size, String token) {
    return invoke(
        allocationCircuit,
        () ->
            client
                .get()
                .uri(
                    builder ->
                        builder
                            .path("/api/rooms/recommend")
                            .queryParam("hotelId", hotelId)
                            .queryParam("startDate", startDate)
                            .queryParam("endDate", endDate)
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                .headers(
                    headers -> {
                      headers.setBearerAuth(token);
                      trace(headers);
                    })
                .retrieve()
                .body(JsonNode.class));
  }

  private <T> T invoke(CircuitBreaker circuit, Supplier<T> action) {
    try {
      return circuit.executeSupplier(Retry.decorateSupplier(retry, action));
    } catch (RestClientResponseException exception) {
      HttpStatus status =
          switch (exception.getStatusCode().value()) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
          };
      throw new HotelCallException(status, "Hotel request failed", exception);
    } catch (RuntimeException exception) {
      if (exception instanceof HotelCallException hotelException) {
        throw hotelException;
      }
      throw new HotelCallException(
          HttpStatus.SERVICE_UNAVAILABLE, "Hotel service unavailable", exception);
    }
  }

  private void internalHeaders(HttpHeaders headers) {
    headers.setBearerAuth(tokens.internal());
    trace(headers);
  }

  private void trace(HttpHeaders headers) {
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      headers.set("X-Trace-Id", traceId);
    }
  }

  private static boolean transientFailure(Throwable error) {
    if (error instanceof RestClientResponseException response) {
      return response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429;
    }
    if (error instanceof ResourceAccessException) {
      return true;
    }
    // RestClient may wrap a socket timeout while reading the response body.
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.net.SocketTimeoutException
          || cause instanceof java.net.SocketException
          || cause instanceof java.net.http.HttpTimeoutException
          || cause instanceof java.io.EOFException) {
        return true;
      }
    }
    return false;
  }
}
