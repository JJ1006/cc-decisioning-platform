package ca.maplenorthern.cc.application;

import ca.maplenorthern.cc.common.web.ApiException;
import ca.maplenorthern.cc.common.web.CorrelationContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Reads decisions from decision-query-api, the only reader of Elasticsearch. */
@Component
public class QueryClient {
  private static final Logger LOG = LoggerFactory.getLogger(QueryClient.class);
  private final RestClient http;

  public QueryClient(@Value("${api.query-url}") String url) {
    // HTTP/1.1 only: no h2c upgrade attempt against Tomcat
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * @return the newest decision of the application (decision-query-api's Decision), if indexed
   */
  public Optional<JsonNode> latestDecision(String applicationId) {
    try {
      return fetch(applicationId);
    } catch (ResourceAccessException first) {
      // an idle keep-alive connection the server already closed fails the first try: GET is safe to
      // repeat
      try {
        return fetch(applicationId);
      } catch (RuntimeException e) {
        return unavailable(e);
      }
    } catch (RuntimeException e) {
      return unavailable(e);
    }
  }

  private Optional<JsonNode> fetch(String applicationId) {
    try {
      JsonNode body =
          http.get()
              .uri("/v1/decisions/{id}", applicationId)
              .header(
                  CorrelationContext.HEADER, String.valueOf(MDC.get(CorrelationContext.MDC_KEY)))
              .retrieve()
              .body(JsonNode.class);
      return Optional.ofNullable(body);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  private static Optional<JsonNode> unavailable(RuntimeException e) {
    LOG.warn("decision-query-api did not answer: {}", e.toString());
    throw new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Decision query unavailable",
        "The decision store did not answer, retry later");
  }
}
