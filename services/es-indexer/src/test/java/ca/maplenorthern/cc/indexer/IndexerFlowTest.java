package ca.maplenorthern.cc.indexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.AuditPayload;
import ca.maplenorthern.cc.common.events.DecisionPayload;
import ca.maplenorthern.cc.common.events.DlqPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.Events;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.ReasonCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Kafka and real Elasticsearch (TLS, auth on); es-setup.sh creates the role, user, templates,
 * aliases.
 */
@Testcontainers
@SpringBootTest
class IndexerFlowTest {
  static final String ELASTIC_PW = "elastic-test-pw";
  static final String INDEXER_PW = "indexer-test-pw";

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

  static Path caFile;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    caFile = Files.createTempFile("es-ca", ".crt");
    Files.write(caFile, ES.caCertAsBytes().orElseThrow());
    runSetup();
    r.add("platform.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("platform.kafka.max-retries", () -> "1");
    r.add("es.url", () -> "https://localhost:" + ES.getMappedPort(9200));
    r.add("es.username", () -> "es-indexer");
    r.add("es.password", () -> INDEXER_PW);
    r.add("es.truststore", () -> caFile.toString());
  }

  static void runSetup() throws Exception {
    File root = new File(System.getProperty("repo.root", "../.."));
    ProcessBuilder pb = new ProcessBuilder("bash", new File(root, "scripts/es-setup.sh").getPath());
    pb.environment().put("ES_URL", "https://localhost:" + ES.getMappedPort(9200));
    pb.environment().put("ES_CACERT", caFile.toString());
    pb.environment().put("ES_DIR", new File(root, "deploy/es").getPath());
    pb.environment().put("ELASTIC_PASSWORD", ELASTIC_PW);
    pb.environment().put("KIBANA_PASSWORD", "kibana-test-pw");
    pb.environment().put("ES_INDEXER_PASSWORD", INDEXER_PW);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    assertThat(p.waitFor()).as("es-setup.sh output:%n%s", out).isZero();
  }

  // ---- helpers
  static JsonNode esGet(String path, String user, String pw) throws Exception {
    return Json.mapper().readTree(es("GET", path, null, user, pw).body());
  }

  static HttpResponse<String> es(String method, String path, String body, String user, String pw)
      throws Exception {
    var tm = ca.maplenorthern.cc.common.net.TlsContexts.trusting(caFile.toString(), "", "PKCS12");
    HttpClient c = HttpClient.newBuilder().sslContext(tm).build();
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("https://localhost:" + ES.getMappedPort(9200) + path))
            .header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes()))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  static long count(String index) throws Exception {
    es("POST", "/" + index + "/_refresh", null, "elastic", ELASTIC_PW);
    return esGet("/" + index + "/_count", "elastic", ELASTIC_PW).path("count").asLong();
  }

  static void send(String topic, String key, String value) throws Exception {
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("key.serializer", StringSerializer.class.getName());
    p.put("value.serializer", StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      producer.send(new ProducerRecord<>(topic, key, value)).get();
    }
  }

  static Envelope<DecisionPayload> decision(String id, Outcome o) {
    return Envelope.of(
        o == Outcome.APPROVE ? EventTypes.APPROVED : EventTypes.DECLINED,
        "corr-" + id,
        "decision-orchestrator",
        new DecisionPayload(
            id,
            o,
            o == Outcome.APPROVE ? new BigDecimal("5000") : BigDecimal.ZERO,
            null,
            null,
            List.of(ReasonCode.R_LOW_SCORE),
            List.of("ELIG-01"),
            "1.0",
            Instant.now(),
            7,
            null));
  }

  @Test
  void indexesMasksDeduplicatesAndDeadLettersMappingErrors() throws Exception {
    // least privilege: the indexer cannot create indices or read
    assertThat(es("PUT", "/cc-evil", "{}", "es-indexer", INDEXER_PW).statusCode()).isEqualTo(403);
    assertThat(es("GET", "/cc-decisions/_search", null, "es-indexer", INDEXER_PW).statusCode())
        .isEqualTo(403);

    var a = decision("A1", Outcome.APPROVE);
    send(Topics.APPROVED, "A1", Events.write(a));
    send(Topics.APPROVED, "A1", Events.write(a)); // duplicate delivery, same eventId
    send(Topics.DECLINED, "D1", Events.write(decision("D1", Outcome.DECLINE)));
    var audit =
        Envelope.of(
            EventTypes.AUDIT,
            "corr-au",
            "decision-orchestrator",
            new AuditPayload(
                "svc",
                "TEST",
                "application",
                "A1",
                AuditPayload.Result.SUCCESS,
                Map.of(
                    "sin",
                    "046454286",
                    "postalCode",
                    "M5V 2T6",
                    "firstName",
                    "Avery",
                    "ok",
                    "yes")));
    send(Topics.AUDIT, "A1", Events.write(audit));
    // a field the strict mapping does not know -> HTTP 400 for that document only
    String bad =
        Events.write(decision("BAD1", Outcome.DECLINE))
            .replace("\"applicationId\"", "\"surprise\":1,\"applicationId\"");
    send(Topics.DECLINED, "BAD1", bad);
    send(Topics.DECLINED, "NOTJSON", "{broken");

    await()
        .atMost(Duration.ofSeconds(90))
        .until(() -> count("cc-decisions") >= 2 && count("cc-audit") >= 1);
    assertThat(count("cc-decisions"))
        .as("duplicate and bad documents are not indexed")
        .isEqualTo(2);

    JsonNode doc = esGet("/cc-audit/_search", "elastic", ELASTIC_PW).at("/hits/hits/0/_source");
    String text = doc.toString();
    assertThat(text).doesNotContain("046454286", "Avery", "M5V 2T6");
    assertThat(doc.at("/payload/details/fsa").asText()).isEqualTo("M5V");
    assertThat(doc.at("/payload/details/firstInitial").asText()).isEqualTo("A.");
    assertThat(doc.at("/topic").asText()).isEqualTo(Topics.AUDIT);

    // today's date-suffixed index sits behind the write alias
    String today =
        java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"));
    assertThat(
            esGet("/_alias/cc-decisions-write", "elastic", ELASTIC_PW).has("cc-decisions-" + today))
        .isTrue();

    // both the mapping error and the unreadable message are in the DLQ, with the original payload
    List<String> dlq = new ArrayList<>();
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("group.id", "t-" + System.nanoTime());
    p.put("auto.offset.reset", "earliest");
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(Topics.dlq(Topics.DECLINED)));
      await()
          .atMost(Duration.ofSeconds(60))
          .until(
              () -> {
                c.poll(Duration.ofMillis(300)).forEach(r -> dlq.add(r.value()));
                return dlq.size() >= 2;
              });
    }
    var errors =
        dlq.stream()
            .map(
                v -> {
                  try {
                    return Events.read(v, DlqPayload.class).payload();
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                })
            .toList();
    assertThat(errors)
        .extracting(DlqPayload::originalValue)
        .anyMatch(v -> v.equals("{broken"))
        .anyMatch(v -> v.contains("surprise"));
    assertThat(errors)
        .extracting(DlqPayload::errorMessage)
        .anyMatch(m -> m.contains("strict_dynamic_mapping_exception"));
  }
}
