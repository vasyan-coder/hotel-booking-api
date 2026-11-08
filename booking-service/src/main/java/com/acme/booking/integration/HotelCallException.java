package com.acme.booking.integration;

import org.springframework.http.HttpStatus;

public class HotelCallException extends RuntimeException {
  private final HttpStatus status;

  public HotelCallException(HttpStatus status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public HttpStatus getStatus() {
    return status;
  }
}
