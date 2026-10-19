package ca.maplenorthern.cc.orchestrator;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.net.TlsContexts;
import ca.maplenorthern.cc.xom.Applicant;
import ca.maplenorthern.cc.xom.Application;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the ccApproval ruleset on ODM RES. HTTPS trusts only the private CA; basic auth comes from
 * the environment. Transient failures (I/O, timeouts, 5xx) are retried, repeated failure opens a
 * circuit breaker, and a 4xx is treated as a bad request and never retried.
 */
@Component
public class ResClient {

  /** Fields the BOM does not expose are dropped before the request is sent. */
  @JsonIgnoreProperties({"dateOfBirth", "sin"})
  abstract static class ApplicantOnWire {}

  private final RestClient http;
  private final ResProperties props;
  private final Retry retry;
  private final CircuitBreaker breaker;
  private final ObjectMapper wire =
      Json.mapper()
          .copy()
          .setSerializationInclusion(JsonInclude.Include.NON_NULL)
          .addMixIn(Applicant.class, ApplicantOnWire.class);

  public ResClient(ResProperties props) {
    this.props = props;
    HttpClient.Builder client =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.connectTimeoutMs()));
    if (props.keystore() != null && !props.keystore().isBlank()) {
      // mutual TLS: ODM Liberty requires a client certificate signed by our CA (the orchestrator's
      // own)
      client.sslContext(
          TlsContexts.mutual(
              props.keystore(),
              props.keystorePassword(),
              props.truststore(),
              props.truststorePassword()));
    } else if (props.truststore() != null && !props.truststore().isBlank()) {
      client.sslContext(
          TlsContexts.trusting(
              props.truststore(), props.truststorePassword(), props.truststoreType()));
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client.build());
    factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
    RestClient.Builder builder =
        RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory);
    if (props.username() != null && !props.username().isBlank()) {
      String token =
          Base64.getEncoder()
              .encodeToString((props.username() + ":" + props.password()).getBytes());
      builder.defaultHeader("Authorization", "Basic " + token);
    }
    this.http = builder.build();
    this.retry =
        Retry.of(
            "res",
            RetryConfig.custom()
                .maxAttempts(props.retryAttempts())
                .waitDuration(Duration.ofMillis(props.retryWaitMs()))
                .retryOnException(
                    e ->
                        e instanceof ResourceAccessException
                            || e instanceof HttpServerErrorException)
                .build());
    this.breaker =
        CircuitBreaker.of(
            "res",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .recordException(
                    e ->
                        e instanceof ResourceAccessException
                            || e instanceof HttpServerErrorException)
                .build());
  }

  /**
   * @param app the application to decide
   * @return the ruleset's decision
   * @throws IllegalArgumentException when the application cannot be sent (poison)
   */
  public RuleDecision execute(Application app) {
    if (app.getRequestedLimit() == null) {
      throw new IllegalArgumentException("requestedLimit is mandatory for ccApproval");
    }
    ObjectNode body = Json.mapper().createObjectNode();
    body.set("application", wire.valueToTree(app));
    Supplier<String> call =
        () ->
            http.post()
                .uri(props.path())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
    String json =
        Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, call)).get();
    try {
      JsonNode decision = Json.mapper().readTree(json).path("decision");
      if (decision.isMissingNode() || decision.isNull()) {
        throw new IllegalStateException("RES response has no decision: " + abbreviate(json));
      }
      return Json.mapper().treeToValue(decision, RuleDecision.class);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read RES response: " + abbreviate(json), e);
    }
  }

  /**
   * @return the ruleset version recorded on every decision
   */
  public String rulesetVersion() {
    return props.rulesetVersion();
  }

  /**
   * @return the ruleset path recorded in audit events
   */
  public String rulesetPath() {
    return props.path().replace("/DecisionService/rest", "");
  }

  private volatile Instant deployedAt;
  private volatile long deployedAtCheckedMillis;

  /**
   * @return when the ruleset was deployed to RES (asked from RES's management API, cached for ten
   *     minutes, one minute after a failure), or null when RES does not say
   */
  public Instant rulesetDeployedAt() {
    long now = System.currentTimeMillis();
    long ttl = deployedAt == null ? 60_000 : 600_000;
    if (now - deployedAtCheckedMillis < ttl) {
      return deployedAt;
    }
    deployedAtCheckedMillis = now;
    try {
      String[] seg = props.path().replaceFirst("^/DecisionService/rest/", "").split("/");
      // the management API lists the rulesets of a RuleApp with their creation date
      String json =
          http.get()
              .uri("/res/api/v1/ruleapps/{a}/{av}/rulesets", seg[0], seg[1])
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(String.class);
      String id = seg[0] + "/" + seg[1] + "/" + seg[2] + "/" + seg[3];
      for (JsonNode n : Json.mapper().readTree(json)) {
        if (id.equals(n.path("id").asText()) && n.hasNonNull("creationDate")) {
          deployedAt = Instant.parse(n.get("creationDate").asText());
          break;
        }
      }
    } catch (RuntimeException | IOException e) {
      // best effort: a decision must never fail because RES cannot tell its deployment date
    }
    return deployedAt;
  }

  CircuitBreaker breaker() {
    return breaker;
  }

  private static String abbreviate(String s) {
    return s == null ? "null" : s.length() > 200 ? s.substring(0, 200) + "..." : s;
  }
}
