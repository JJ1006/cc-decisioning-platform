package ca.maplenorthern.cc.common.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer error handling: bounded in-place retries with exponential backoff, then the record goes
 * to {@code <topic>.dlq}. Poison messages skip the retries. The offset is committed after the
 * record is dead-lettered, so a failing record never blocks a partition.
 */
public final class ErrorHandling {
  private ErrorHandling() {}

  /**
   * @param kafka template used to publish to the DLQ (idempotent producer)
   * @param producerName service name recorded in the DLQ envelope
   * @param maxRetries retries before dead-lettering
   */
  public static DefaultErrorHandler errorHandler(
      KafkaOperations<String, String> kafka, String producerName, int maxRetries) {
    ExponentialBackOff backOff = new ExponentialBackOff(200L, 2.0);
    backOff.setMaxElapsedTime(30_000L);
    DefaultErrorHandler handler =
        new DefaultErrorHandler(new DlqRecoverer(kafka, producerName, maxRetries + 1), backOff);
    handler.addNotRetryableExceptions(
        DeserializationException.class,
        JsonProcessingException.class,
        IllegalArgumentException.class);
    handler.setCommitRecovered(true);
    return handler;
  }
}
