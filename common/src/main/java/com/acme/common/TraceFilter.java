package com.acme.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class TraceFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Trace-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String traceId =
        incoming != null && incoming.matches("[A-Za-z0-9-]{1,64}")
            ? incoming
            : UUID.randomUUID().toString();
    String previous = MDC.get("traceId");
    MDC.put("traceId", traceId);
    response.setHeader(HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      if (previous == null) MDC.remove("traceId");
      else MDC.put("traceId", previous);
    }
  }
}
