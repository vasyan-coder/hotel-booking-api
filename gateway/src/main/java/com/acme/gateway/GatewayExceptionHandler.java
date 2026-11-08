package com.acme.gateway;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);
  private final ErrorResponseWriter errors;

  public GatewayExceptionHandler(ErrorResponseWriter errors) {
    this.errors = errors;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(exception);
    }
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    if (exception instanceof ResponseStatusException responseException) {
      status = HttpStatus.resolve(responseException.getStatusCode().value());
      if (status == null) {
        status = HttpStatus.INTERNAL_SERVER_ERROR;
      }
    } else if (hasCause(exception, TimeoutException.class)) {
      status = HttpStatus.GATEWAY_TIMEOUT;
    } else if (hasCause(exception, ConnectException.class)) {
      status = HttpStatus.BAD_GATEWAY;
    }
    log.atWarn()
        .addKeyValue("event", "gateway_error")
        .addKeyValue("traceId", exchange.getAttributeOrDefault(TraceFilter.ATTRIBUTE, ""))
        .addKeyValue("status", status.value())
        .setCause(exception)
        .log("Gateway request failed");
    return errors.write(exchange, status, status.name(), status.getReasonPhrase());
  }

  private boolean hasCause(Throwable exception, Class<? extends Throwable> expected) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (expected.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }
}
