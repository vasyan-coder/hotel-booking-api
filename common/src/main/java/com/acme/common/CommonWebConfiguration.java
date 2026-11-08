package com.acme.common;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@Import(ApiExceptionHandler.class)
public class CommonWebConfiguration {
  @Bean
  FilterRegistrationBean<TraceFilter> traceFilter() {
    FilterRegistrationBean<TraceFilter> filter = new FilterRegistrationBean<>(new TraceFilter());
    filter.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return filter;
  }
}
