package ca.maplenorthern.cc.application;

import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.Events;
import ca.maplenorthern.cc.common.web.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes events in one Kafka transaction, keyed by application id. */
@Component
public class EventPublisher {
  private static final Logger LOG = LoggerFactory.getLogger(EventPublisher.class);

  /** An event for a topic. */
  public record Out(String topic, Envelope<?> event) {}

  private final KafkaTemplate<String, String> kafka;

  public EventPublisher(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  /** All events are published or none (transactional producer, idempotent). */
  public void publish(String applicationId, List<Out> events) {
    try {
      kafka.executeInTransaction(
          ops -> {
            for (Out out : events) {
              ops.send(out.topic(), applicationId, Events.write(out.event()));
            }
            return null;
          });
    } catch (RuntimeException e) {
      LOG.error("cannot publish events for {}", applicationId, e);
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Event bus unavailable",
          "The request could not be recorded, retry later");
    }
  }
}
