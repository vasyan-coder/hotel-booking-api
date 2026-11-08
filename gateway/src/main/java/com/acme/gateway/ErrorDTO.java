package com.acme.gateway;

import java.time.Instant;
import java.util.Map;

public record ErrorDTO(
    Instant timestamp,
    int status,
    String code,
    String message,
    String path,
    String traceId,
    Map<String, String> details) {}
