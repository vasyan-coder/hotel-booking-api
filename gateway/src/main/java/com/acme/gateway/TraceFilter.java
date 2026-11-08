package com.acme.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements WebFilter {
  public static final String HEADER = "X-Trace-Id";
  public static final String ATTRIBUTE = "traceId";
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
    String traceId =
        incoming != null && SAFE_ID.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    exchange.getAttributes().put(ATTRIBUTE, traceId);
    exchange.getResponse().getHeaders().set(HEADER, traceId);
    var request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  headers.set(HEADER, traceId);
                  headers.remove("X-User-Id");
                  headers.remove("X-User-Roles");
                })
            .build();
    long started = System.nanoTime();
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              var status = exchange.getResponse().getStatusCode();
              log.atInfo()
                  .addKeyValue("event", "gateway_request")
                  .addKeyValue("traceId", traceId)
                  .addKeyValue("method", request.getMethod().name())
                  .addKeyValue("path", request.getPath().value())
                  .addKeyValue("status", status == null ? 200 : status.value())
                  .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                  .log("Gateway response committed");
              return Mono.empty();
            });
    return chain.filter(exchange.mutate().request(request).build());
  }
}
