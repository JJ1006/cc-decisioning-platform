package ca.maplenorthern.cc.common.kafka;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.DlqPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * Publishes a failed record, wrapped in a {@code cc.dlq.v1} envelope, to {@code <topic>.dlq}. The
 * original value is kept verbatim so poison messages can be inspected and replayed.
 */
public class DlqRecoverer implements ConsumerRecordRecoverer {
  private final KafkaOperations<String, String> kafka;
  private final String producer;
  private final int attempts;

  public DlqRecoverer(KafkaOperations<String, String> kafka, String producer, int attempts) {
    this.kafka = kafka;
    this.producer = producer;
    this.attempts = attempts;
  }

  @Override
  public void accept(ConsumerRecord<?, ?> failed, Exception exception) {
    Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
    Object recordKey = failed.key();
    String key = recordKey == null ? null : recordKey.toString();
    DlqPayload payload =
        new DlqPayload(
            failed.topic(),
            failed.partition(),
            failed.offset(),
            key,
            failed.value() == null ? "" : failed.value().toString(),
            cause.getClass().getName(),
            String.valueOf(cause.getMessage()),
            Instant.now(),
            attempts);
    try {
      String json =
          Json.mapper()
              .writeValueAsString(
                  Envelope.of(EventTypes.DLQ, correlationId(failed), producer, payload));
      kafka.send(Topics.dlq(failed.topic()), key, json).get();
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize DLQ envelope", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while publishing to DLQ", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("DLQ publish failed", e.getCause());
    }
  }

  private static String correlationId(ConsumerRecord<?, ?> failed) {
    var header = failed.headers().lastHeader("correlationId");
    return header == null ? "unknown" : new String(header.value(), StandardCharsets.UTF_8);
  }
}
