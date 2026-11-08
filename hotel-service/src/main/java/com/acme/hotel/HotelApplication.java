package com.acme.hotel;

import com.acme.common.CommonWebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(CommonWebConfiguration.class)
public class HotelApplication {
  public static void main(String[] args) {
    SpringApplication.run(HotelApplication.class, args);
  }
}
