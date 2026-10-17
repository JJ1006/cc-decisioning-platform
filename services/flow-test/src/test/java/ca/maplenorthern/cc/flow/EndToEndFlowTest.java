package ca.maplenorthern.cc.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.net.TlsContexts;
import ca.maplenorthern.cc.generator.GeneratorApplication;
import ca.maplenorthern.cc.generator.GeneratorService;
import ca.maplenorthern.cc.indexer.IndexerApplication;
import ca.maplenorthern.cc.notification.NotificationApplication;
import ca.maplenorthern.cc.orchestrator.OrchestratorApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole event path in one JVM with real Kafka and a real (TLS, authenticated) Elasticsearch:
 * generator -> orchestrator (RES stubbed by WireMock) -> notification-svc and es-indexer. Each
 * service runs in its own Spring context from its own application.yml.
 */
@Testcontainers
class EndToEndFlowTest {
  static final String ELASTIC_PW = "elastic-flow-pw";
  static final String INDEXER_PW = "indexer-flow-pw";

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  @Container
  static final ElasticsearchContainer ES =
      new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.19.23")
          .withPassword(ELASTIC_PW)
          .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withEnv("xpack.ml.enabled", "false")
          .withStartupTimeout(Duration.ofMinutes(4));

  /** Stands in for ODM RES: approve at score 650 and above, decline below. */
  static class ScoreRules implements ResponseDefinitionTransformerV2 {
    @Override
    public ResponseDefinition transform(ServeEvent event) {
      try {
        JsonNode app =
            Json.mapper().readTree(event.getRequest().getBodyAsString()).path("application");
        int score = app.path("bureau").path("score").asInt();
        String decision =
            score >= 650
                ? "{\"outcome\":\"APPROVE\",\"creditLimit\":5000,\"product\":\"NO_FEE\",\"aprTier\":\"TIER_3\","
                    + "\"reasonCodes\":[\"R_APPROVED_STANDARD\"],\"firedRules\":[\"RSN-01\"]}"
                : "{\"outcome\":\"DECLINE\",\"creditLimit\":0,\"reasonCodes\":[\"R_LOW_SCORE\"],\"firedRules\":[\"RISK-DT-01\"]}";
        return new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder()
            .withHeader("Content-Type", "application/json")
            .withBody("{\"decision\":" + decision + "}")
            .build();
      } catch (Exception e) {
        return ResponseDefinition.notConfigured();
      }
    }

    @Override
    public String getName() {
      return "score-rules";
    }

    @Override
    public boolean applyGlobally() {
      return true;
    }
  }

  static final WireMockServer RES =
      new WireMockServer(
          WireMockConfiguration.options().dynamicPort().extensions(new ScoreRules()));
  static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();
  static Path caFile;

  @AfterAll
  static void stop() {
    CONTEXTS.forEach(ConfigurableApplicationContext::close);
    RES.stop();
  }

  static File root() {
    return new File(System.getProperty("repo.root", "../.."));
  }

  static ConfigurableApplicationContext start(Class<?> app, String service, String... extra) {
    List<String> args = new ArrayList<>();
    args.add(
        "--spring.config.location=file:"
            + root()
            + "/services/"
            + service
            + "/src/main/resources/application.yml");
    args.add("--server.port=0");
    args.add("--platform.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
    args.add("--platform.kafka.max-retries=1");
    args.addAll(List.of(extra));
    ConfigurableApplicationContext ctx = SpringApplication.run(app, args.toArray(String[]::new));
    CONTEXTS.add(ctx);
    return ctx;
  }

  static void runEsSetup() throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder("bash", new File(root(), "scripts/es-setup.sh").getPath());
    var env = pb.environment();
    env.put("ES_URL", "https://localhost:" + ES.getMappedPort(9200));
    env.put("ES_CACERT", caFile.toString());
    env.put("ES_DIR", new File(root(), "deploy/es").getPath());
    env.put("ELASTIC_PASSWORD", ELASTIC_PW);
    env.put("KIBANA_PASSWORD", "kibana-flow-pw");
    env.put("ES_INDEXER_PASSWORD", INDEXER_PW);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    assertThat(p.waitFor()).as("es-setup.sh:%n%s", out).isZero();
  }

  static JsonNode es(String method, String path) throws Exception {
    HttpClient c =
        HttpClient.newBuilder()
            .sslContext(TlsContexts.trusting(caFile.toString(), "", "PKCS12"))
            .build();
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("https://localhost:" + ES.getMappedPort(9200) + path))
            .header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(("elastic:" + ELASTIC_PW).getBytes()))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return Json.mapper().readTree(c.send(req, HttpResponse.BodyHandlers.ofString()).body());
  }

  static long count(String index) throws Exception {
    es("POST", "/" + index + "/_refresh");
    return es("GET", "/" + index + "/_count").path("count").asLong();
  }

  /**
   * Counts data records (offsets would also count the transaction markers of transactional topics).
   */
  static long topicCount(String topic) {
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("group.id", "count-" + System.nanoTime());
    p.put("isolation.level", "read_committed");
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      var parts = c.partitionsFor(topic);
      if (parts == null) return 0;
      var tps =
          parts.stream()
              .map(i -> new org.apache.kafka.common.TopicPartition(topic, i.partition()))
              .toList();
      c.assign(tps);
      c.seekToBeginning(tps);
      var end = c.endOffsets(tps);
      long n = 0;
      while (tps.stream().anyMatch(tp -> c.position(tp) < end.get(tp))) {
        n += c.poll(Duration.ofMillis(300)).count();
      }
      return n;
    }
  }

  @Test
  void generatorToOrchestratorToIndexer() throws Exception {
    RES.start();
    caFile = Files.createTempFile("es-ca", ".crt");
    Files.write(caFile, ES.caCertAsBytes().orElseThrow());
    runEsSetup();
    String esUrl = "https://localhost:" + ES.getMappedPort(9200);

    start(
        OrchestratorApplication.class,
        "decision-orchestrator",
        "--res.base-url=http://localhost:" + RES.port(),
        "--res.username=tester",
        "--res.password=secret",
        "--res.retry-wait-ms=10");
    start(NotificationApplication.class, "notification-svc");
    start(
        IndexerApplication.class,
        "es-indexer",
        "--es.url=" + esUrl,
        "--es.username=es-indexer",
        "--es.password=" + INDEXER_PW,
        "--es.truststore=" + caFile);
    GeneratorService generator =
        start(GeneratorApplication.class, "txn-generator").getBean(GeneratorService.class);

    int total = 150;
    assertThat(generator.start(300, total, 0.08, 21)).isTrue();
    await().atMost(Duration.ofSeconds(60)).until(() -> !generator.isRunning());
    generator.stop();
    long poison = (Long) generator.status().get("poison");
    long good = total - poison;
    assertThat(poison).as("some poison was generated").isPositive();

    // every good application is decided exactly once and audited; poison is dead-lettered
    await()
        .atMost(Duration.ofSeconds(120))
        .untilAsserted(
            () -> {
              assertThat(topicCount(Topics.APPROVED) + topicCount(Topics.DECLINED)).isEqualTo(good);
              assertThat(topicCount(Topics.AUDIT)).isEqualTo(good);
              assertThat(topicCount(Topics.NOTIFICATIONS)).isEqualTo(good);
              assertThat(topicCount(Topics.dlq(Topics.SUBMITTED))).isEqualTo(poison);
              assertThat(count("cc-decisions")).isEqualTo(good);
              assertThat(count("cc-audit")).isEqualTo(good);
              assertThat(count("cc-notifications")).isEqualTo(good);
            });

    // Elasticsearch agrees with Kafka on the outcome split
    long approvedInEs =
        es("GET", "/cc-decisions/_count?q=payload.outcome:APPROVE").path("count").asLong();
    assertThat(approvedInEs).isEqualTo(topicCount(Topics.APPROVED));
    // nothing personal was indexed
    for (String index : List.of("cc-decisions", "cc-audit", "cc-notifications")) {
      String hits = es("GET", "/" + index + "/_search?size=200").toString();
      assertThat(hits)
          .doesNotContain("\"sin\"", "postalCode", "dateOfBirth", "firstName", "lastName");
    }
  }
}
