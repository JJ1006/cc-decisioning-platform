package ca.maplenorthern.cc.indexer;

import ca.maplenorthern.cc.common.kafka.DlqRecoverer;
import ca.maplenorthern.cc.common.kafka.PlatformKafkaConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;

/** Indexes decisions, audit events and notifications into Elasticsearch after masking PII. */
@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties(EsProperties.class)
@Import(PlatformKafkaConfig.class)
public class IndexerApplication {
  public static void main(String[] args) {
    SpringApplication.run(IndexerApplication.class, args);
  }

  @Bean
  DlqRecoverer dlqRecoverer(
      @Qualifier("dlqTemplate") KafkaTemplate<String, String> dlqTemplate,
      @Value("${spring.application.name}") String name,
      @Value("${platform.kafka.max-retries:3}") int maxRetries) {
    return new DlqRecoverer(dlqTemplate, name, maxRetries + 1);
  }
}
