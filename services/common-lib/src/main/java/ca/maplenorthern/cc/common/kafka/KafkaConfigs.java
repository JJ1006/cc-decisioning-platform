package ca.maplenorthern.cc.common.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.listener.ContainerProperties;

/** Kafka client property builders: idempotent producers, manual-commit consumers, mTLS. */
public final class KafkaConfigs {

  /** Keystore and truststore locations for an mTLS client. */
  public record Tls(
      String keystorePath,
      String keystorePassword,
      String truststorePath,
      String truststorePassword,
      String storeType) {}

  private KafkaConfigs() {}

  /** Idempotent, acks=all producer sending String keys and String (JSON) values. */
  public static Map<String, Object> producerProps(String bootstrapServers, Tls tls) {
    Map<String, Object> p = new HashMap<>();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    p.put(ProducerConfig.ACKS_CONFIG, "all");
    p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
    applyTls(p, tls);
    return p;
  }

  /** Consumer with auto-commit off (acknowledge manually) reading committed data only. */
  public static Map<String, Object> consumerProps(
      String bootstrapServers, String groupId, Tls tls) {
    Map<String, Object> p = new HashMap<>();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    applyTls(p, tls);
    return p;
  }

  /** Sets the listener container to acknowledge each record explicitly after processing. */
  public static void manualAck(ContainerProperties props) {
    props.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
  }

  private static void applyTls(Map<String, Object> p, Tls tls) {
    if (tls == null) {
      return;
    }
    p.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
    p.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, tls.storeType());
    p.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, tls.keystorePath());
    p.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, tls.keystorePassword());
    p.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, tls.storeType());
    p.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, tls.truststorePath());
    p.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, tls.truststorePassword());
  }
}
