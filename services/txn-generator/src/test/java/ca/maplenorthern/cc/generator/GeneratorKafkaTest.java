package ca.maplenorthern.cc.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.ApplicationSubmittedPayload;
import ca.maplenorthern.cc.common.events.Events;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class GeneratorKafkaTest {
  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("platform.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired GeneratorService service;

  @Test
  void producesKeyedEnvelopesAndPoison() throws Exception {
    assertThat(service.start(500, 200, 0.1, 5)).isTrue();
    assertThat(service.start(500, 200, 0.1, 5)).as("second start while running").isFalse();
    await().atMost(Duration.ofSeconds(30)).until(() -> !service.isRunning());
    service.stop();

    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("group.id", "test-" + System.nanoTime());
    p.put("auto.offset.reset", "earliest");
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> got = new ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(Topics.SUBMITTED));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                c.poll(Duration.ofMillis(300)).forEach(got::add);
                return got.size() >= 200;
              });
    }
    long poison = got.stream().filter(r -> r.key().startsWith("POISON-")).count();
    assertThat(got).hasSize(200);
    assertThat(poison).isBetween(5L, 40L);
    for (ConsumerRecord<String, String> r : got) {
      if (r.key().startsWith("POISON-")) {
        continue;
      }
      var env = Events.read(r.value(), ApplicationSubmittedPayload.class);
      assertThat(env.payload().application().getApplicationId()).isEqualTo(r.key());
      assertThat(env.producer()).isEqualTo("txn-generator");
      assertThat(r.headers().lastHeader("correlationId")).isNotNull();
    }
    Map<String, Object> status = service.status();
    assertThat(status.get("sent")).isEqualTo(200L);
  }
}
