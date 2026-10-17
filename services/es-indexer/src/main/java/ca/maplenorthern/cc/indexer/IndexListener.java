package ca.maplenorthern.cc.indexer;

import ca.maplenorthern.cc.common.Topics;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** Batch consumer: one _bulk request per poll, acknowledged after Elasticsearch accepted it. */
@Component
public class IndexListener {
  private static final Logger LOG = LoggerFactory.getLogger(IndexListener.class);
  private final IndexingService service;

  public IndexListener(IndexingService service) {
    this.service = service;
  }

  @KafkaListener(
      topics = {
        Topics.APPROVED,
        Topics.DECLINED,
        Topics.REFERRED,
        Topics.MANUAL,
        Topics.AUDIT,
        Topics.NOTIFICATIONS
      },
      containerFactory = "batchListenerContainerFactory")
  public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
    service.index(records);
    ack.acknowledge();
    LOG.info("indexed batch of {}", records.size());
  }
}
