package com.acme.booking.security;

import com.acme.booking.domain.User;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private final JwtEncoder encoder;

  public TokenService(JwtEncoder encoder) {
    this.encoder = encoder;
  }

  public String forUser(User user) {
    return encode(user.getId().toString(), List.of(user.getRole().name()));
  }

  public String internal() {
    return encode("booking-service", List.of("INTERNAL"));
  }

  private String encode(String subject, List<String> roles) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("hotel-booking")
            .subject(subject)
            .audience(List.of("hotel-api"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .claim("roles", roles)
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
