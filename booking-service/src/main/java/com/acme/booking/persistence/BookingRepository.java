package com.acme.booking.persistence;

import com.acme.booking.domain.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
  Optional<Booking> findByUserIdAndRequestId(Long userId, UUID requestId);

  Page<Booking> findByUserId(Long userId, Pageable pageable);

  List<Booking> findByUserIdAndStatusNot(Long userId, BookingStatus status);

  List<Booking> findByStatusAndCreatedAtBefore(
      BookingStatus status, Instant cutoff, Pageable pageable);

  List<Booking> findByCompensationRequiredTrue(Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Booking b where b.id = :id")
  Optional<Booking> lockById(@Param("id") UUID id);
}
