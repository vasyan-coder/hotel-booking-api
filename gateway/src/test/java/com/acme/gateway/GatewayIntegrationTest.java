package com.acme.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "security.jwt-secret=" + GatewayIntegrationTest.SECRET,
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false"
    })
@AutoConfigureWebTestClient
class GatewayIntegrationTest {
  static final String SECRET = "gateway-integration-test-secret-32-bytes-minimum";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final AtomicInteger BACKEND_REQUESTS = new AtomicInteger();
  private static final DisposableServer BACKEND =
      HttpServer.create()
          .host("127.0.0.1")
          .port(0)
          .handle(
              (request, response) -> {
                BACKEND_REQUESTS.incrementAndGet();
                try {
                  String body =
                      JSON.writeValueAsString(
                          Map.of(
                              "path", request.uri(),
                              "traceId", request.requestHeaders().get("X-Trace-Id", ""),
                              "authorization", request.requestHeaders().get("Authorization", ""),
                              "userId", request.requestHeaders().get("X-User-Id", "")));
                  return response
                      .header("Content-Type", "application/json")
                      .sendString(Mono.just(body));
                } catch (Exception exception) {
                  return Mono.error(exception);
                }
              })
          .bindNow();

  @Autowired private WebTestClient client;

  @DynamicPropertySource
  static void backendRoutes(DynamicPropertyRegistry registry) {
    String address = "http://127.0.0.1:" + BACKEND.port();
    registry.add("routes.booking", () -> address);
    registry.add("routes.hotel", () -> address);
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.disposeNow();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/auth/login", "/api/auth/register"})
  void anonymousCanReachAuthenticationEndpoints(String path) {
    client
        .post()
        .uri(path)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Trace-Id")
        .expectBody()
        .jsonPath("$.path")
        .isEqualTo(path);
  }

  @Test
  void anonymousCanCheckHealth() {
    client
        .get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  void protectedRouteRequiresBearerTokenAndReturnsStructuredError() {
    client
        .get()
        .uri("/api/bookings")
        .header("X-Trace-Id", "test-missing-token")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals("WWW-Authenticate", "Bearer")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHORIZED")
        .jsonPath("$.status")
        .isEqualTo(401)
        .jsonPath("$.path")
        .isEqualTo("/api/bookings")
        .jsonPath("$.traceId")
        .isEqualTo("test-missing-token")
        .jsonPath("$.timestamp")
        .exists();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/bookings", "/api/users/1", "/api/hotels", "/api/rooms"})
  void authorizedRequestsReachBackendAndForwardJwtAndTrace(String path) throws Exception {
    String role = path.startsWith("/api/users/") ? "ADMIN" : "USER";
    String token = token(role, "hotel-booking", "hotel-api", Instant.now().plusSeconds(300));
    client
        .get()
        .uri(path)
        .headers(headers -> headers.setBearerAuth(token))
        .header("X-Trace-Id", "test-trace-123")
        .header("X-User-Id", "spoofed")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Trace-Id", "test-trace-123")
        .expectBody()
        .jsonPath("$.path")
        .isEqualTo(path)
        .jsonPath("$.authorization")
        .isEqualTo("Bearer " + token)
        .jsonPath("$.traceId")
        .isEqualTo("test-trace-123")
        .jsonPath("$.userId")
        .isEqualTo("");
  }

  @Test
  void userCannotCreateHotelEvenWhenPayloadIsInvalid() throws Exception {
    int callsBefore = BACKEND_REQUESTS.get();
    String token = userToken();
    client
        .post()
        .uri("/api/hotels")
        .headers(headers -> headers.setBearerAuth(token))
        .header("Content-Type", "application/json")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
    org.assertj.core.api.Assertions.assertThat(BACKEND_REQUESTS.get()).isEqualTo(callsBefore);
  }

  @Test
  void unsafeTraceIdIsReplaced() throws Exception {
    String token = userToken();
    client
        .get()
        .uri("/api/rooms")
        .headers(headers -> headers.setBearerAuth(token))
        .header("X-Trace-Id", "invalid trace id")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueMatches("X-Trace-Id", "[a-f0-9-]{36}")
        .expectBody()
        .jsonPath("$.traceId")
        .value(
            value ->
                org.assertj.core.api.Assertions.assertThat((String) value)
                    .matches("[a-f0-9-]{36}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/rooms/allocate",
        "/api/rooms/release",
        "/api/rooms/1/confirm-availability",
        "/api/rooms/1/release",
        "/api/internal/anything"
      })
  void internalEndpointsAreNeverExposedEvenToAdmin(String path) throws Exception {
    int callsBefore = BACKEND_REQUESTS.get();
    String admin = token("ADMIN", "hotel-booking", "hotel-api", Instant.now().plusSeconds(300));
    client
        .post()
        .uri(path)
        .headers(headers -> headers.setBearerAuth(admin))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
    org.assertj.core.api.Assertions.assertThat(BACKEND_REQUESTS.get()).isEqualTo(callsBefore);
  }

  @Test
  void internalServiceRoleCannotUsePublicRoutes() throws Exception {
    String internal =
        token("INTERNAL", "hotel-booking", "hotel-api", Instant.now().plusSeconds(300));
    client
        .get()
        .uri("/api/hotels")
        .headers(headers -> headers.setBearerAuth(internal))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/docs/booking", "/docs/hotel"})
  void documentationRoutesRewritePath(String path) throws Exception {
    String token = userToken();
    client
        .get()
        .uri(path)
        .headers(headers -> headers.setBearerAuth(token))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.path")
        .isEqualTo("/v3/api-docs");
  }

  @Test
  void invalidSignatureIsUnauthorized() {
    client
        .get()
        .uri("/api/hotels")
        .headers(headers -> headers.setBearerAuth("not.a.jwt"))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void validTokenSignedWithDifferentSecretIsUnauthorized() throws Exception {
    SignedJWT original = SignedJWT.parse(userToken());
    SignedJWT jwt = new SignedJWT(original.getHeader(), original.getJWTClaimsSet());
    jwt.sign(new MACSigner("another-secret-that-has-more-than-32-bytes"));
    assertUnauthorized(jwt.serialize());
  }

  @Test
  void anonymousCannotCallInternalEndpoints() {
    client
        .post()
        .uri("/api/rooms/allocate")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void unknownRouteReturnsUnifiedNotFound() throws Exception {
    String token = userToken();
    client
        .get()
        .uri("/missing-route")
        .headers(headers -> headers.setBearerAuth(token))
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NOT_FOUND")
        .jsonPath("$.traceId")
        .isNotEmpty();
  }

  @Test
  void expiredTokenIsUnauthorized() throws Exception {
    assertUnauthorized(
        token("USER", "hotel-booking", "hotel-api", Instant.now().minusSeconds(120)));
  }

  @Test
  void incorrectIssuerIsUnauthorized() throws Exception {
    assertUnauthorized(token("USER", "other-issuer", "hotel-api", Instant.now().plusSeconds(300)));
  }

  @Test
  void incorrectAudienceIsUnauthorized() throws Exception {
    assertUnauthorized(token("USER", "hotel-booking", "other-api", Instant.now().plusSeconds(300)));
  }

  @Test
  void missingAudienceIsUnauthorized() throws Exception {
    assertUnauthorized(token("USER", "hotel-booking", null, Instant.now().plusSeconds(300)));
  }

  private void assertUnauthorized(String token) {
    client
        .get()
        .uri("/api/hotels")
        .headers(headers -> headers.setBearerAuth(token))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHORIZED");
  }

  private String userToken() throws JOSEException {
    return token("USER", "hotel-booking", "hotel-api", Instant.now().plusSeconds(300));
  }

  private String token(String role, String issuer, String audience, Instant expires)
      throws JOSEException {
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .subject("1")
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(180)))
                .expirationTime(Date.from(expires))
                .claim("roles", List.of(role))
                .build());
    jwt.sign(new MACSigner(SECRET));
    return jwt.serialize();
  }
}
