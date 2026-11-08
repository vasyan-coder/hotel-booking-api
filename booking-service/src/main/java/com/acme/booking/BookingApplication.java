package com.acme.booking;

import com.acme.common.CommonWebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(CommonWebConfiguration.class)
public class BookingApplication {
  public static void main(String[] args) {
    SpringApplication.run(BookingApplication.class, args);
  }
}
