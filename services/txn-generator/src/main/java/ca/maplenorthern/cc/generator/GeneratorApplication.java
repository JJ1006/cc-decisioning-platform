package ca.maplenorthern.cc.generator;

import ca.maplenorthern.cc.common.kafka.PlatformKafkaConfig;
import ca.maplenorthern.cc.common.web.ApiSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Synthetic application generator. Start and stop it through {@code /admin/*}. */
@SpringBootApplication
@Import({PlatformKafkaConfig.class, ApiSupport.class})
public class GeneratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(GeneratorApplication.class, args);
  }
}
