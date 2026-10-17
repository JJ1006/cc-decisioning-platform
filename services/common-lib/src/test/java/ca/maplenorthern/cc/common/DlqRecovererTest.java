package ca.maplenorthern.cc.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.maplenorthern.cc.common.kafka.DlqRecoverer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;

class DlqRecovererTest {
  @Test
  @SuppressWarnings("unchecked")
  void publishesEnvelopeWithOriginalValueToDlqTopic() throws Exception {
    KafkaOperations<String, String> kafka = mock(KafkaOperations.class);
    when(kafka.send(any(String.class), any(String.class), any(String.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    new DlqRecoverer(kafka, "decision-orchestrator", 4)
        .accept(
            new ConsumerRecord<>(Topics.SUBMITTED, 2, 17L, "APP-1", "{not json"),
            new RuntimeException("boom"));

    ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(eq(Topics.dlq(Topics.SUBMITTED)), eq("APP-1"), value.capture());
    JsonNode env = Json.mapper().readTree(value.getValue());
    assertThat(env.get("eventType").asText()).isEqualTo("cc.dlq.v1");
    assertThat(env.get("producer").asText()).isEqualTo("decision-orchestrator");
    JsonNode p = env.get("payload");
    assertThat(p.get("originalValue").asText()).isEqualTo("{not json");
    assertThat(p.get("originalOffset").asLong()).isEqualTo(17);
    assertThat(p.get("attempts").asInt()).isEqualTo(4);
  }
}
