package com.acme.hotel.config;

import com.acme.hotel.domain.*;
import com.acme.hotel.repository.*;
import java.math.BigDecimal;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "app.seed-data", havingValue = "true", matchIfMissing = true)
public class DemoDataConfiguration {
  @Bean
  ApplicationRunner seedHotels(
      HotelRepository hotels, RoomRepository rooms, TransactionTemplate tx) {
    return args ->
        tx.executeWithoutResult(
            status -> {
              if (hotels.count() != 0) return;
              Hotel first =
                  hotels.save(new Hotel("Невский", "Невский проспект, 15", "Санкт-Петербург"));
              Hotel second = hotels.save(new Hotel("Арбат", "Арбат, 20", "Москва"));
              rooms.save(new Room(first, "101", true, new BigDecimal("4500.00"), 2));
              rooms.save(new Room(first, "102", true, new BigDecimal("4500.00"), 2));
              rooms.save(new Room(first, "103", true, new BigDecimal("6500.00"), 3));
              rooms.save(new Room(second, "201", true, new BigDecimal("5500.00"), 2));
              rooms.save(new Room(second, "202", false, new BigDecimal("7000.00"), 4));
            });
  }
}
