package ca.maplenorthern.cc.indexer;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.net.TlsContexts;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Minimal Elasticsearch _bulk client over TLS with basic auth (no heavy client library). */
@Component
public class EsClient {

  /** One document to create. */
  public record Doc(String index, String id, String json) {}

  /** Outcome of one bulk item. */
  public record ItemResult(int status, String errorType, String reason) {
    public boolean ok() {
      return status >= 200 && status < 300 || status == 409; // 409: already indexed, idempotent
    }

    public boolean retryable() {
      return status == 429 || status >= 500;
    }
  }

  private final RestClient http;

  public EsClient(EsProperties props) {
    HttpClient.Builder client =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.timeoutMs()));
    if (props.truststore() != null && !props.truststore().isBlank()) {
      client.sslContext(
          TlsContexts.trusting(
              props.truststore(), props.truststorePassword(), props.truststoreType()));
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client.build());
    factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
    RestClient.Builder b = RestClient.builder().baseUrl(props.url()).requestFactory(factory);
    if (props.username() != null && !props.username().isBlank()) {
      b.defaultHeader(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString((props.username() + ":" + props.password()).getBytes()));
    }
    this.http = b.build();
  }

  /**
   * @return one result per document, in order
   * @throws IllegalStateException when the request as a whole failed (the caller retries)
   */
  public List<ItemResult> bulk(List<Doc> docs) {
    StringBuilder nd = new StringBuilder();
    for (Doc d : docs) {
      nd.append("{\"create\":{\"_index\":\"")
          .append(d.index())
          .append("\",\"_id\":\"")
          .append(d.id())
          .append("\"}}\n");
      nd.append(d.json()).append('\n');
    }
    String body =
        http.post()
            .uri("/_bulk")
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .body(nd.toString())
            .retrieve()
            .body(String.class);
    try {
      JsonNode items = Json.mapper().readTree(body).path("items");
      if (items.size() != docs.size()) {
        throw new IllegalStateException(
            "bulk returned " + items.size() + " items for " + docs.size());
      }
      List<ItemResult> out = new ArrayList<>();
      for (JsonNode item : items) {
        JsonNode r = item.elements().next();
        JsonNode err = r.path("error");
        out.add(
            new ItemResult(
                r.path("status").asInt(),
                err.path("type").asText(null),
                err.path("reason").asText(null)));
      }
      return out;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("cannot read bulk response", e);
    }
  }
}
