package com.acme.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutes {
  @Bean
  RouteLocator applicationRoutes(
      RouteLocatorBuilder builder,
      @Value("${routes.booking:lb://booking-service}") String bookingUri,
      @Value("${routes.hotel:lb://hotel-service}") String hotelUri) {
    return builder
        .routes()
        .route(
            "booking",
            route ->
                route.path("/api/auth/**", "/api/bookings/**", "/api/users/**").uri(bookingUri))
        .route("hotel", route -> route.path("/api/hotels/**", "/api/rooms/**").uri(hotelUri))
        .route(
            "booking-docs",
            route ->
                route
                    .path("/docs/booking")
                    .filters(filters -> filters.setPath("/v3/api-docs"))
                    .uri(bookingUri))
        .route(
            "hotel-docs",
            route ->
                route
                    .path("/docs/hotel")
                    .filters(filters -> filters.setPath("/v3/api-docs"))
                    .uri(hotelUri))
        .build();
  }
}
