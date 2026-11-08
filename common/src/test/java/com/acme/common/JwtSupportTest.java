package com.acme.common;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class JwtSupportTest {
  private static final String SECRET = "test-secret-with-at-least-thirty-two-bytes";

  private String token(String issuer, String audience, Instant expires) {
    return JwtSupport.encoder(SECRET)
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .issuer(issuer)
                    .audience(List.of(audience))
                    .subject("42")
                    .issuedAt(Instant.now().minusSeconds(3600))
                    .expiresAt(expires)
                    .claim("roles", List.of("USER"))
                    .build()))
        .getTokenValue();
  }

  @Test
  void acceptsSignedTokenAndMapsRoles() {
    var jwt =
        JwtSupport.decoder(SECRET)
            .decode(token("hotel-booking", "hotel-api", Instant.now().plusSeconds(600)));
    assertThat(JwtSupport.authorities().convert(jwt).getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_USER");
  }

  @Test
  void rejectsWrongAudience() {
    assertThatThrownBy(
            () ->
                JwtSupport.decoder(SECRET)
                    .decode(token("hotel-booking", "another-api", Instant.now().plusSeconds(600))))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void rejectsWrongIssuer() {
    assertThatThrownBy(
            () ->
                JwtSupport.decoder(SECRET)
                    .decode(token("untrusted", "hotel-api", Instant.now().plusSeconds(600))))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void rejectsExpiredToken() {
    assertThatThrownBy(
            () ->
                JwtSupport.decoder(SECRET)
                    .decode(token("hotel-booking", "hotel-api", Instant.now().minusSeconds(120))))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void rejectsWrongSignature() {
    assertThatThrownBy(
            () ->
                JwtSupport.decoder(SECRET + "different")
                    .decode(token("hotel-booking", "hotel-api", Instant.now().plusSeconds(600))))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsShortSecret() {
    assertThatThrownBy(() -> JwtSupport.decoder("short"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
