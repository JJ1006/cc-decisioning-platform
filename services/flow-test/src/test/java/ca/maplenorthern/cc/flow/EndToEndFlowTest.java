package ca.maplenorthern.cc.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.generator.GeneratorApplication;
import ca.maplenorthern.cc.generator.GeneratorService;
import ca.maplenorthern.cc.notification.NotificationApplication;
import ca.maplenorthern.cc.orchestrator.OrchestratorApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The event path up to Kafka in one JVM with a real broker: generator -> orchestrator (RES stubbed
 * by WireMock) -> notification-svc. Each service runs in its own Spring context from its own
 * application.yml. Elasticsearch ingestion (Logstash) is tested by scripts/test-logstash.sh and
 * make demo.
 */
@Testcontainers
class EndToEndFlowTest {

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

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
        return new ResponseDefinitionBuilder()
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

  /** Counts data records (offsets would also count the commit markers of transactional topics). */
  static long topicCount(String topic) {
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("group.id", "count-" + System.nanoTime());
    p.put("isolation.level", "read_committed");
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      var parts = c.partitionsFor(topic);
      if (parts == null) {
        return 0;
      }
      var tps = parts.stream().map(i -> new TopicPartition(topic, i.partition())).toList();
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
  void generatorToOrchestratorToNotifications() {
    RES.start();
    start(
        OrchestratorApplication.class,
        "decision-orchestrator",
        "--res.base-url=http://localhost:" + RES.port(),
        "--res.username=" + java.util.UUID.randomUUID(),
        "--res.password=" + java.util.UUID.randomUUID(),
        "--res.retry-wait-ms=10");
    start(NotificationApplication.class, "notification-svc");
    GeneratorService generator =
        start(GeneratorApplication.class, "txn-generator").getBean(GeneratorService.class);

    int total = 150;
    assertThat(generator.start(300, total, 0.08, 21)).isTrue();
    await().atMost(Duration.ofSeconds(60)).until(() -> !generator.isRunning());
    generator.stop();
    long poison = (Long) generator.status().get("poison");
    long good = total - poison;
    assertThat(poison).as("some poison was generated").isPositive();

    // every good application is decided exactly once, audited and notified; poison is dead-lettered
    await()
        .atMost(Duration.ofSeconds(120))
        .untilAsserted(
            () -> {
              assertThat(topicCount(Topics.APPROVED) + topicCount(Topics.DECLINED)).isEqualTo(good);
              assertThat(topicCount(Topics.AUDIT)).isEqualTo(good);
              assertThat(topicCount(Topics.NOTIFICATIONS)).isEqualTo(good);
              assertThat(topicCount(Topics.dlq(Topics.SUBMITTED))).isEqualTo(poison);
            });
    assertThat(topicCount(Topics.APPROVED)).isPositive();
    assertThat(topicCount(Topics.DECLINED)).isPositive();
  }
}
