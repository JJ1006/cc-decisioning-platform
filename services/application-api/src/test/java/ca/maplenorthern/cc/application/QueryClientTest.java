package ca.maplenorthern.cc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.maplenorthern.cc.common.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The client of decision-query-api: 404 is "no decision", a dropped connection is retried once. */
class QueryClientTest {
  HttpServer server;
  final AtomicInteger calls = new AtomicInteger();

  QueryClient serving(java.util.function.Consumer<com.sun.net.httpserver.HttpExchange> handler)
      throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/decisions/",
        ex -> {
          calls.incrementAndGet();
          handler.accept(ex);
        });
    server.start();
    return new QueryClient("http://127.0.0.1:" + server.getAddress().getPort());
  }

  static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        ex.getResponseBody().write(bytes);
      }
      ex.close();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void returnsTheDecision() throws Exception {
    QueryClient client = serving(ex -> reply(ex, 200, "{\"outcome\":\"REFER\"}"));
    assertThat(client.latestDecision("A1"))
        .get()
        .extracting(n -> n.path("outcome").asText())
        .isEqualTo("REFER");
  }

  @Test
  void notFoundIsEmpty() throws Exception {
    QueryClient client = serving(ex -> reply(ex, 404, "{}"));
    assertThat(client.latestDecision("A1")).isEmpty();
    assertThat(calls).hasValue(1);
  }

  @Test
  void aDroppedConnectionIsRetriedOnce() throws Exception {
    QueryClient client =
        serving(
            ex -> {
              if (calls.get() == 1) {
                ex.close(); // no response: what a keep-alive connection closed by the server
                // looks like
              } else {
                reply(ex, 200, "{\"outcome\":\"APPROVE\"}");
              }
            });
    assertThat(client.latestDecision("A1")).isPresent();
    assertThat(calls).hasValue(2);
  }

  @Test
  void serverErrorIs503WithoutLeakingDetails() throws Exception {
    QueryClient client = serving(ex -> reply(ex, 500, "{\"secret\":\"stack\"}"));
    assertThatThrownBy(() -> client.latestDecision("A1"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.getMessage()).doesNotContain("secret");
            });
  }
}
