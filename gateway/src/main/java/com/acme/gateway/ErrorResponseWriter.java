package com.acme.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ErrorResponseWriter {
  private final ObjectMapper mapper;

  public ErrorResponseWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Mono<Void> write(
      ServerWebExchange exchange, HttpStatus status, String code, String message) {
    var response = exchange.getResponse();
    if (response.isCommitted()) {
      return Mono.empty();
    }
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    response.getHeaders().setCacheControl("no-store");
    var error =
        new ErrorDTO(
            Instant.now(),
            status.value(),
            code,
            message,
            exchange.getRequest().getPath().value(),
            exchange.getAttributeOrDefault(TraceFilter.ATTRIBUTE, ""),
            Map.of());
    try {
      var buffer = response.bufferFactory().wrap(mapper.writeValueAsBytes(error));
      return response.writeWith(Mono.just(buffer));
    } catch (JsonProcessingException exception) {
      return Mono.error(exception);
    }
  }
}
