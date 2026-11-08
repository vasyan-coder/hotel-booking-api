package com.acme.booking.persistence;

import com.acme.booking.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByUsername(String username);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> lockById(@Param("id") Long id);
}
