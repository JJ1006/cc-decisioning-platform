package ca.maplenorthern.cc.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.DecisionPayload;
import ca.maplenorthern.cc.common.events.DlqPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.Events;
import ca.maplenorthern.cc.common.events.NotificationPayload;
import ca.maplenorthern.cc.common.events.NotificationPayload.NoticeType;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.ReasonCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class NotificationFlowTest {
  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("platform.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("platform.kafka.max-retries", () -> "1");
  }

  private static void send(String topic, String key, String value) throws Exception {
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("key.serializer", StringSerializer.class.getName());
    p.put("value.serializer", StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      producer.send(new ProducerRecord<>(topic, key, value)).get();
    }
  }

  private static String decision(String id, Outcome o, ReasonCode... codes) {
    return Events.write(
        Envelope.of(
            o == Outcome.APPROVE
                ? EventTypes.APPROVED
                : o == Outcome.DECLINE ? EventTypes.DECLINED : EventTypes.REFERRED,
            "corr-" + id,
            "decision-orchestrator",
            new DecisionPayload(
                id,
                o,
                o == Outcome.APPROVE ? new BigDecimal("5000") : BigDecimal.ZERO,
                null,
                null,
                List.of(codes),
                List.of("X"),
                "1.0",
                Instant.now(),
                5,
                null)));
  }

  private static List<ConsumerRecord<String, String>> read(String topic, int n) {
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("group.id", "t-" + System.nanoTime());
    p.put("auto.offset.reset", "earliest");
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> got = new ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(topic));
      await()
          .atMost(Duration.ofSeconds(60))
          .until(
              () -> {
                c.poll(Duration.ofMillis(300)).forEach(got::add);
                return got.size() >= n;
              });
    }
    return got;
  }

  @Test
  void eachDecisionTopicProducesTheRightNotice_andPoisonGoesToDlq() throws Exception {
    send(Topics.APPROVED, "A1", decision("A1", Outcome.APPROVE, ReasonCode.R_APPROVED_STANDARD));
    send(Topics.DECLINED, "D1", decision("D1", Outcome.DECLINE, ReasonCode.R_ACTIVE_BANKRUPTCY));
    send(Topics.REFERRED, "R1", decision("R1", Outcome.REFER, ReasonCode.R_BORDERLINE_SCORE));
    send(Topics.DECLINED, "BAD", "{not json");

    var notices = read(Topics.NOTIFICATIONS, 3);
    var byApp = new java.util.HashMap<String, Envelope<NotificationPayload>>();
    for (var r : notices) {
      var env = Events.read(r.value(), NotificationPayload.class);
      byApp.put(env.payload().applicationId(), env);
      assertThat(env.producer()).isEqualTo("notification-svc");
      assertThat(r.key()).isEqualTo(env.payload().applicationId());
    }
    assertThat(byApp.get("A1").payload().noticeType()).isEqualTo(NoticeType.APPROVAL);
    assertThat(byApp.get("D1").payload().noticeType()).isEqualTo(NoticeType.ADVERSE_ACTION);
    assertThat(byApp.get("D1").payload().reasonTexts())
        .containsExactly("Applicant has an active bankruptcy");
    assertThat(byApp.get("R1").payload().noticeType()).isEqualTo(NoticeType.REFERRAL);
    assertThat(byApp.get("D1").correlationId()).isEqualTo("corr-D1");

    var dlq = read(Topics.dlq(Topics.DECLINED), 1);
    assertThat(Events.read(dlq.get(0).value(), DlqPayload.class).payload().originalValue())
        .isEqualTo("{not json");
  }
}
