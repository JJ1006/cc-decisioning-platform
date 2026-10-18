package ca.maplenorthern.cc.common.kafka;

import ca.maplenorthern.cc.common.kafka.KafkaConfigs.Tls;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.KafkaTransactionManager;

/**
 * Kafka wiring shared by the event services: mTLS from {@code platform.kafka.*} properties,
 * idempotent producers, manual-ack consumers and the retry-then-DLQ error handler. Set {@code
 * platform.kafka.transactional-id-prefix} to make the main producer transactional
 * (consume-transform-produce); DLQ publishing always uses a separate non-transactional template.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformKafkaConfig {

  @Value("${platform.kafka.bootstrap-servers:localhost:9093}")
  String bootstrap;

  @Value("${platform.kafka.keystore:}")
  String keystore;

  @Value("${platform.kafka.truststore:}")
  String truststore;

  @Value("${platform.kafka.store-password:}")
  String storePassword;

  /**
   * Separate truststore password (Strimzi issues different ones); blank means the keystore
   * password.
   */
  @Value("${platform.kafka.truststore-password:}")
  String truststorePassword;

  @Value("${platform.kafka.store-type:PKCS12}")
  String storeType;

  @Value("${platform.kafka.group-id:${spring.application.name:app}}")
  String groupId;

  @Value("${platform.kafka.max-retries:3}")
  int maxRetries;

  @Value("${platform.kafka.transactional-id-prefix:}")
  String txPrefix;

  @Value("${platform.kafka.concurrency:1}")
  int concurrency;

  @Value("${platform.kafka.max-poll-records:100}")
  int maxPollRecords;

  @Value("${spring.application.name:app}")
  String appName;

  private Tls tls() {
    if (keystore.isBlank()) {
      return null;
    }
    String trustPassword = truststorePassword.isBlank() ? storePassword : truststorePassword;
    return new Tls(keystore, storePassword, truststore, trustPassword, storeType);
  }

  @Bean
  ProducerFactory<String, String> producerFactory() {
    Map<String, Object> props = KafkaConfigs.producerProps(bootstrap, tls());
    var pf = new DefaultKafkaProducerFactory<String, String>(props);
    if (!txPrefix.isBlank()) {
      pf.setTransactionIdPrefix(txPrefix);
    }
    return pf;
  }

  @Bean
  @Primary
  KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }

  @Bean
  KafkaTemplate<String, String> dlqTemplate() {
    return new KafkaTemplate<>(
        new DefaultKafkaProducerFactory<String, String>(
            KafkaConfigs.producerProps(bootstrap, tls())));
  }

  @Bean
  ConsumerFactory<String, String> consumerFactory() {
    Map<String, Object> props = KafkaConfigs.consumerProps(bootstrap, groupId, tls());
    props.put("max.poll.records", maxPollRecords);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      @Qualifier("dlqTemplate") KafkaTemplate<String, String> dlqTemplate,
      ProducerFactory<String, String> producerFactory) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.setConcurrency(concurrency);
    factory.setCommonErrorHandler(ErrorHandling.errorHandler(dlqTemplate, appName, maxRetries));
    if (txPrefix.isBlank()) {
      KafkaConfigs.manualAck(factory.getContainerProperties());
    } else {
      // offsets are committed inside the producer transaction (sendOffsetsToTransaction)
      factory
          .getContainerProperties()
          .setKafkaAwareTransactionManager(new KafkaTransactionManager<>(producerFactory));
    }
    return factory;
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> batchListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      @Qualifier("dlqTemplate") KafkaTemplate<String, String> dlqTemplate) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.setConcurrency(concurrency);
    factory.setBatchListener(true);
    factory.setCommonErrorHandler(ErrorHandling.errorHandler(dlqTemplate, appName, maxRetries));
    KafkaConfigs.manualAck(factory.getContainerProperties());
    return factory;
  }
}
