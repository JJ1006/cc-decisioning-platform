package ca.maplenorthern.cc.notification;

import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.DecisionPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.Events;
import ca.maplenorthern.cc.common.events.NotificationPayload;
import ca.maplenorthern.cc.common.web.CorrelationContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Reads the three decision topics and publishes one notice per decision. At-least-once: the offset
 * is acknowledged after the notice is acknowledged by the broker, and the notice event id is
 * derived from the decision event id so a redelivery produces an identical event that the indexer
 * ignores.
 */
@Component
public class DecisionNoticeListener {
  private static final Logger LOG = LoggerFactory.getLogger(DecisionNoticeListener.class);
  private final KafkaTemplate<String, String> kafka;

  public DecisionNoticeListener(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  @KafkaListener(
      topics = {Topics.APPROVED, Topics.DECLINED, Topics.REFERRED},
      containerFactory = "kafkaListenerContainerFactory")
  public void onDecision(ConsumerRecord<String, String> record, Acknowledgment ack)
      throws Exception {
    var header = record.headers().lastHeader("correlationId");
    String headerCorr = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    String corr = CorrelationContext.set(headerCorr);
    try {
      Envelope<DecisionPayload> in = Events.read(record.value(), DecisionPayload.class);
      if (headerCorr == null) {
        corr = CorrelationContext.set(in.correlationId());
      }
      NotificationPayload notice = NoticeComposer.compose(in.payload());
      Envelope<NotificationPayload> out =
          new Envelope<>(
              UUID.nameUUIDFromBytes(("notice:" + in.eventId()).getBytes(StandardCharsets.UTF_8)),
              corr,
              EventTypes.NOTIFICATION,
              1,
              Instant.now(),
              "notification-svc",
              notice);
      ProducerRecord<String, String> rec =
          new ProducerRecord<>(
              Topics.NOTIFICATIONS, in.payload().applicationId(), Events.write(out));
      rec.headers().add("correlationId", corr.getBytes(StandardCharsets.UTF_8));
      kafka.send(rec).get(10, TimeUnit.SECONDS);
      ack.acknowledge();
      LOG.info("notice {} for {}", notice.noticeType(), in.payload().applicationId());
    } finally {
      CorrelationContext.clear();
    }
  }
}
