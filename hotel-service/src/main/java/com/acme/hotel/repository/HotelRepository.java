package com.acme.hotel.repository;

import com.acme.hotel.domain.Hotel;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface HotelRepository
    extends JpaRepository<Hotel, Long>, JpaSpecificationExecutor<Hotel> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select h from Hotel h where h.id = :id")
  Optional<Hotel> lockById(@Param("id") Long id);
}
