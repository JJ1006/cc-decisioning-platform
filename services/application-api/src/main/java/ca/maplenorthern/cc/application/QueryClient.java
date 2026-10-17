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
import org.springframework.web.client.RestClient;

/** Reads decisions from decision-query-api, the only reader of Elasticsearch. */
@Component
public class QueryClient {
  private static final Logger LOG = LoggerFactory.getLogger(QueryClient.class);
  private final RestClient http;

  public QueryClient(@Value("${api.query-url}") String url) {
    var factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * @return the newest decision of the application (decision-query-api's Decision), if indexed
   */
  public Optional<JsonNode> latestDecision(String applicationId) {
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
    } catch (RuntimeException e) {
      LOG.warn("decision-query-api did not answer: {}", e.toString());
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Decision query unavailable",
          "The decision store did not answer, retry later");
    }
  }
}
