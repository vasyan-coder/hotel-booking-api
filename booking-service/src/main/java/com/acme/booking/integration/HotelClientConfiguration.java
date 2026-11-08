package com.acme.booking.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HotelClientConfiguration {
  @Bean("hotelRestClientBuilder")
  @LoadBalanced
  @ConditionalOnProperty(name = "hotel.discovery", havingValue = "true", matchIfMissing = true)
  RestClient.Builder discoveryBuilder() {
    return RestClient.builder();
  }

  @Bean("hotelRestClientBuilder")
  @ConditionalOnProperty(name = "hotel.discovery", havingValue = "false")
  RestClient.Builder directBuilder() {
    return RestClient.builder();
  }

  @Bean
  RestClient hotelRestClient(
      RestClient.Builder hotelRestClientBuilder,
      @Value("${hotel.base-url}") String baseUrl,
      @Value("${hotel.connect-timeout-ms:1000}") int connectTimeout,
      @Value("${hotel.read-timeout-ms:2000}") int readTimeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeout);
    factory.setReadTimeout(readTimeout);
    return hotelRestClientBuilder.requestFactory(factory).baseUrl(baseUrl).build();
  }
}
