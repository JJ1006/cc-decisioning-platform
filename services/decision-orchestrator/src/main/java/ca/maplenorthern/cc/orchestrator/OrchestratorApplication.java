package ca.maplenorthern.cc.orchestrator;

import ca.maplenorthern.cc.common.kafka.PlatformKafkaConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;

/** Consumes submitted applications, asks ODM RES for a decision and routes the result. */
@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties(ResProperties.class)
@Import(PlatformKafkaConfig.class)
public class OrchestratorApplication {
  public static void main(String[] args) {
    SpringApplication.run(OrchestratorApplication.class, args);
  }
}
