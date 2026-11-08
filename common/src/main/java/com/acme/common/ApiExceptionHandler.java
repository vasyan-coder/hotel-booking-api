package com.acme.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorDTO> api(ApiException e, HttpServletRequest r) {
    return error(e.getStatus(), e.getCode(), e.getMessage(), r, Map.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorDTO> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
    Map<String, String> details = new LinkedHashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> details.put(f.getField(), f.getDefaultMessage()));
    return error(
        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", r, details);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    ConstraintViolationException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ErrorDTO> badRequest(Exception e, HttpServletRequest r) {
    return error(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Invalid request parameters or body",
        r,
        Map.of());
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    PessimisticLockingFailureException.class
  })
  ResponseEntity<ErrorDTO> conflict(Exception e, HttpServletRequest r) {
    return error(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "Operation conflicts with existing data; retry if concurrent",
        r,
        Map.of());
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ErrorDTO> denied(Exception e, HttpServletRequest r) {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", r, Map.of());
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ErrorDTO> unauthenticated(Exception e, HttpServletRequest r) {
    return error(
        HttpStatus.UNAUTHORIZED,
        "UNAUTHORIZED",
        "Authentication required or credentials invalid",
        r,
        Map.of());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ErrorDTO> notFound(Exception e, HttpServletRequest r) {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", r, Map.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorDTO> unexpected(Exception e, HttpServletRequest r) {
    if (e instanceof org.springframework.web.ErrorResponse webError
        && webError.getStatusCode().is4xxClientError()) {
      HttpStatus status = HttpStatus.valueOf(webError.getStatusCode().value());
      return error(status, "HTTP_" + status.value(), status.getReasonPhrase(), r, Map.of());
    }
    LoggerFactory.getLogger(getClass())
        .error("event=unhandled_request_error path={}", r.getRequestURI(), e);
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", r, Map.of());
  }

  private ResponseEntity<ErrorDTO> error(
      HttpStatus status,
      String code,
      String message,
      HttpServletRequest r,
      Map<String, String> details) {
    return ResponseEntity.status(status)
        .body(
            new ErrorDTO(
                Instant.now(),
                status.value(),
                code,
                message,
                r.getRequestURI(),
                MDC.get("traceId"),
                details));
  }
}
