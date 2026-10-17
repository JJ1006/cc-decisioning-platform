package ca.maplenorthern.cc.common;

import static org.assertj.core.api.Assertions.assertThat;

import ca.maplenorthern.cc.common.kafka.KafkaConfigs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ContainerProperties;

class KafkaConfigsTest {
  private static final KafkaConfigs.Tls TLS =
      new KafkaConfigs.Tls("/store/client", "pw", "/store/trust", "pw", "PKCS12");

  @Test
  void producerIsIdempotentAndAcksAll() {
    Map<String, Object> p = KafkaConfigs.producerProps("kafka:9093", TLS);
    assertThat(p)
        .containsEntry("enable.idempotence", true)
        .containsEntry("acks", "all")
        .containsEntry("security.protocol", "SSL")
        .containsEntry("ssl.keystore.location", "/store/client");
  }

  @Test
  void consumerCommitsManuallyAndReadsCommitted() {
    Map<String, Object> p = KafkaConfigs.consumerProps("kafka:9093", "g1", TLS);
    assertThat(p)
        .containsEntry("enable.auto.commit", false)
        .containsEntry("isolation.level", "read_committed")
        .containsEntry("group.id", "g1");
  }

  @Test
  void plaintextWhenNoTls() {
    assertThat(KafkaConfigs.producerProps("localhost:9092", null))
        .doesNotContainKey("security.protocol");
  }

  @Test
  void manualAckMode() {
    ContainerProperties props = new ContainerProperties("t");
    KafkaConfigs.manualAck(props);
    assertThat(props.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
  }
}
