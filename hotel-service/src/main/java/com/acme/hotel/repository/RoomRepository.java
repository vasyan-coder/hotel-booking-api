package com.acme.hotel.repository;

import com.acme.hotel.domain.Room;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {
  @Query("select r.hotel.id from Room r where r.id = :id")
  Optional<Long> findHotelId(@Param("id") Long id);

  List<Room> findByHotelIdAndAvailableTrueOrderByTimesBookedAscIdAsc(Long hotelId);

  List<Room> findByHotelIdOrderByIdAsc(Long hotelId);

  boolean existsByHotelIdAndNumber(Long hotelId, String number);

  boolean existsByHotelIdAndNumberAndIdNot(Long hotelId, String number, Long id);
}
