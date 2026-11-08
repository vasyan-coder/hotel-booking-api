package com.acme.booking.service;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class BookingRecovery {
  private final BookingTransactions transactions;
  private final BookingService bookings;
  private final long pendingTimeout;

  public BookingRecovery(
      BookingTransactions transactions,
      BookingService bookings,
      @Value("${app.recovery.pending-timeout-seconds:30}") long pendingTimeout) {
    this.transactions = transactions;
    this.bookings = bookings;
    this.pendingTimeout = pendingTimeout;
  }

  @Scheduled(fixedDelayString = "${app.recovery.delay-ms:5000}")
  public void recover() {
    Instant cutoff = Instant.now().minusSeconds(pendingTimeout);
    for (var id : transactions.pendingBefore(cutoff)) {
      try (var scope = org.slf4j.MDC.putCloseable("traceId", id.toString())) {
        transactions.expirePending(id, cutoff).ifPresent(bookings::compensate);
      }
    }
    for (var booking : transactions.compensations()) {
      try (var scope = org.slf4j.MDC.putCloseable("traceId", booking.id().toString())) {
        bookings.compensate(booking);
      }
    }
  }
}
