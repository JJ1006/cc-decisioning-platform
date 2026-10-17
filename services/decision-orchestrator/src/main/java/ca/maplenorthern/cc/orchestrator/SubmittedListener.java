package ca.maplenorthern.cc.orchestrator;

import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.web.CorrelationContext;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Transactional consume-transform-produce: offsets commit with the produced events. */
@Component
public class SubmittedListener {
  private static final Logger LOG = LoggerFactory.getLogger(SubmittedListener.class);
  private final DecisionRouter router;

  public SubmittedListener(DecisionRouter router) {
    this.router = router;
  }

  @KafkaListener(topics = Topics.SUBMITTED, containerFactory = "kafkaListenerContainerFactory")
  public void onSubmitted(ConsumerRecord<String, String> record) throws Exception {
    var header = record.headers().lastHeader("correlationId");
    String corr =
        CorrelationContext.set(
            header == null ? null : new String(header.value(), StandardCharsets.UTF_8));
    try {
      router.handle(record.value(), corr);
      LOG.info("decided application {}", record.key());
    } finally {
      CorrelationContext.clear();
    }
  }
}
