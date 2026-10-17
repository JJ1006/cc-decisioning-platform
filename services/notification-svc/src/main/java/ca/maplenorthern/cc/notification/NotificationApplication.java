package ca.maplenorthern.cc.notification;

import ca.maplenorthern.cc.common.kafka.PlatformKafkaConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;

/** Turns decisions into customer notices on cc.notifications.v1. Nothing is actually sent. */
@SpringBootApplication
@EnableKafka
@Import(PlatformKafkaConfig.class)
public class NotificationApplication {
  public static void main(String[] args) {
    SpringApplication.run(NotificationApplication.class, args);
  }
}
