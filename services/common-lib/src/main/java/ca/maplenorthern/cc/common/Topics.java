package ca.maplenorthern.cc.common;

import java.util.List;

/** Kafka topic names. See docs/kafka-topics.md. All topics are keyed by applicationId. */
public final class Topics {
  public static final String SUBMITTED = "cc.applications.submitted.v1";
  public static final String APPROVED = "cc.decisions.approved.v1";
  public static final String DECLINED = "cc.decisions.declined.v1";
  public static final String REFERRED = "cc.decisions.referred.v1";
  public static final String MANUAL = "cc.decisions.manual.v1";
  public static final String NOTIFICATIONS = "cc.notifications.v1";
  public static final String AUDIT = "cc.audit.v1";

  /** Topics that carry business events (each also gets a retry and a dlq topic). */
  public static final List<String> BUSINESS =
      List.of(SUBMITTED, APPROVED, DECLINED, REFERRED, MANUAL, NOTIFICATIONS, AUDIT);

  private Topics() {}

  /**
   * @return the non-blocking retry topic for {@code topic}
   */
  public static String retry(String topic) {
    return topic + ".retry";
  }

  /**
   * @return the dead-letter topic for {@code topic}
   */
  public static String dlq(String topic) {
    return topic + ".dlq";
  }
}
