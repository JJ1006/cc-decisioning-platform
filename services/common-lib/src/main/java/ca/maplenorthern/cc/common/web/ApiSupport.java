package ca.maplenorthern.cc.common.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Import this in an HTTP API: correlation id filter plus problem+json error handling. */
@Configuration(proxyBeanMethods = false)
public class ApiSupport {

  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
    var registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(Integer.MIN_VALUE + 10);
    return registration;
  }

  @Bean
  ProblemAdvice problemAdvice() {
    return new ProblemAdvice();
  }
}
