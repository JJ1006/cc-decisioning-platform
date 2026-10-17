package ca.maplenorthern.cc.application;

import ca.maplenorthern.cc.common.kafka.PlatformKafkaConfig;
import ca.maplenorthern.cc.common.web.ApiSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Entry point for submitting applications and recording underwriter decisions. Writes to Kafka
 * only.
 */
@SpringBootApplication
@Import({PlatformKafkaConfig.class, ApiSupport.class})
public class ApplicationApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(ApplicationApiApplication.class, args);
  }
}
