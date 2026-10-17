package ca.maplenorthern.cc.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Common event envelope. Mirrors contracts/envelope.schema.json.
 *
 * @param <T> payload type
 */
public record Envelope<T>(
    UUID eventId,
    String correlationId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    String producer,
    T payload) {

  /** Creates a version-1 envelope with a fresh eventId and the current time. */
  public static <T> Envelope<T> of(
      String eventType, String correlationId, String producer, T payload) {
    return new Envelope<>(
        UUID.randomUUID(), correlationId, eventType, 1, Instant.now(), producer, payload);
  }
}
