package ca.maplenorthern.cc.orchestrator;

import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.ApplicantSummary;
import ca.maplenorthern.cc.common.events.ApplicationSubmittedPayload;
import ca.maplenorthern.cc.common.events.AuditPayload;
import ca.maplenorthern.cc.common.events.DecisionPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.Events;
import ca.maplenorthern.cc.xom.Application;
import ca.maplenorthern.cc.xom.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns one submitted application into a decision event and an audit event. Called inside the
 * listener container's Kafka transaction, so both events and the consumed offset commit together.
 */
@Component
public class DecisionRouter {
  private static final String PRODUCER = "decision-orchestrator";

  private final ResClient res;
  private final KafkaTemplate<String, String> kafka;
  private final Timer latency;
  private final MeterRegistry registry;

  public DecisionRouter(
      ResClient res, KafkaTemplate<String, String> kafka, MeterRegistry registry) {
    this.res = res;
    this.kafka = kafka;
    this.registry = registry;
    this.latency =
        Timer.builder("decision.latency")
            .description("ODM RES decision latency (decisionLatencyMs)")
            .publishPercentileHistogram()
            .register(registry);
  }

  /**
   * @param value the submitted envelope as JSON
   * @param correlationId correlation id to carry on all outputs
   */
  public void handle(String value, String correlationId) throws Exception {
    Envelope<ApplicationSubmittedPayload> in =
        Events.read(value, ApplicationSubmittedPayload.class);
    Application app = in.payload().application();
    if (app == null || app.getApplicationId() == null || app.getApplicationId().isBlank()) {
      throw new IllegalArgumentException("submitted event without applicationId");
    }
    String corr = correlationId != null ? correlationId : in.correlationId();

    long start = System.nanoTime();
    RuleDecision d = res.execute(app);
    long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
    latency.record(Duration.ofMillis(millis));
    if (d.outcome() == null) {
      throw new IllegalStateException("ruleset returned no outcome for " + app.getApplicationId());
    }

    DecisionPayload payload =
        new DecisionPayload(
            app.getApplicationId(),
            d.outcome(),
            d.creditLimit(),
            d.product(),
            d.aprTier(),
            d.reasonCodes(),
            d.firedRules(),
            res.rulesetVersion(),
            Instant.now(),
            millis,
            new ApplicantSummary(
                app.getAddress().getProvince(),
                ApplicantSummary.ageBand(app.getApplicant().getAgeYears()),
                app.getBureau().getScore(),
                Math.round(app.tdsRatio() * 1000.0) / 1000.0,
                app.getEmployment().getAnnualIncome()));
    send(topicFor(d.outcome()), app.getApplicationId(), eventType(d.outcome()), corr, payload);

    AuditPayload audit =
        new AuditPayload(
            PRODUCER,
            "ODM_EXECUTE",
            "application",
            app.getApplicationId(),
            AuditPayload.Result.SUCCESS,
            Map.of(
                "rulesetPath", res.rulesetPath(),
                "outcome", d.outcome().name(),
                "decisionLatencyMs", millis));
    send(Topics.AUDIT, app.getApplicationId(), EventTypes.AUDIT, corr, audit);
    Counter.builder("decisions.total")
        .tag("outcome", d.outcome().name())
        .register(registry)
        .increment();
  }

  static String topicFor(Outcome o) {
    return switch (o) {
      case APPROVE -> Topics.APPROVED;
      case DECLINE -> Topics.DECLINED;
      case REFER -> Topics.REFERRED;
    };
  }

  private static String eventType(Outcome o) {
    return switch (o) {
      case APPROVE -> EventTypes.APPROVED;
      case DECLINE -> EventTypes.DECLINED;
      case REFER -> EventTypes.REFERRED;
    };
  }

  private <T> void send(String topic, String key, String eventType, String corr, T payload) {
    String json = Events.write(Envelope.of(eventType, corr, PRODUCER, payload));
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
    record.headers().add("correlationId", corr.getBytes(StandardCharsets.UTF_8));
    kafka.send(record);
  }
}
