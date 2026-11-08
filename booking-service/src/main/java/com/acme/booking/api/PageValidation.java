package com.acme.booking.api;

import com.acme.common.ApiException;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

public final class PageValidation {
  private PageValidation() {}

  public static void validate(Pageable pageable, Set<String> fields) {
    if (pageable.getSort().stream().anyMatch(order -> !fields.contains(order.getProperty()))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Unsupported sort field");
    }
  }
}
