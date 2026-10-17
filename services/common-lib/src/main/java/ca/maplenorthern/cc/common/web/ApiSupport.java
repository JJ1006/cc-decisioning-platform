package ca.maplenorthern.cc.common.web;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

  /**
   * Mutual TLS identity ({@link ClientCertFilter}); on when {@code
   * platform.security.require-client-cert} is true, which is when Tomcat runs with
   * server.ssl.client-auth=need.
   */
  @Bean
  @ConditionalOnProperty(name = "platform.security.require-client-cert", havingValue = "true")
  FilterRegistrationBean<ClientCertFilter> clientCertFilter(
      @Value("${platform.security.allowed-client-cns:}") String allowed,
      @Value("${platform.security.gateway-cn:apisix}") String gatewayCn) {
    var cns =
        Arrays.stream(allowed.split(","))
            .map(String::trim)
            .filter(c -> !c.isEmpty())
            .collect(Collectors.toSet());
    var registration = new FilterRegistrationBean<>(new ClientCertFilter(cns, gatewayCn));
    registration.setOrder(Integer.MIN_VALUE + 20);
    return registration;
  }

  @Bean
  ProblemAdvice problemAdvice() {
    return new ProblemAdvice();
  }
}
