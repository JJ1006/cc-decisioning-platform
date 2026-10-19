package ca.maplenorthern.cc.orchestrator;

import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.ApplicantSummary;
import ca.maplenorthern.cc.common.events.ApplicantToken;
import ca.maplenorthern.cc.common.events.ApplicationSubmittedPayload;
import ca.maplenorthern.cc.common.events.AuditPayload;
import ca.maplenorthern.cc.common.events.DecisionInputs;
import ca.maplenorthern.cc.common.events.DecisionPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.Events;
import ca.maplenorthern.cc.xom.Application;
import ca.maplenorthern.cc.xom.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
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
  private final Timer resCall;
  private final MeterRegistry registry;
  private final String tokenKey;

  public DecisionRouter(
      ResClient res,
      KafkaTemplate<String, String> kafka,
      MeterRegistry registry,
      @Value("${privacy.applicant-token-key:}") String tokenKey) {
    if (tokenKey == null || tokenKey.isBlank()) {
      throw new IllegalStateException(
          "APPLICANT_TOKEN_KEY is not set: the applicant token needs a secret key");
    }
    this.tokenKey = tokenKey;
    this.res = res;
    this.kafka = kafka;
    this.registry = registry;
    this.latency =
        Timer.builder("decision.latency")
            .description("ODM RES decision latency (decisionLatencyMs)")
            .publishPercentileHistogram()
            .register(registry);
    // res_call_seconds: the ODM RES call as seen by the orchestrator, with histogram buckets for
    // the p95 alert
    this.resCall =
        Timer.builder("res.call")
            .description("ODM RES call duration")
            .publishPercentileHistogram()
            .register(registry);
    // 0 closed, 1 half open, 2 open (anything else, such as disabled, is -1)
    Gauge.builder(
            "res.circuitbreaker.state",
            res,
            r ->
                switch (r.breaker().getState()) {
                  case CLOSED -> 0;
                  case HALF_OPEN -> 1;
                  case OPEN -> 2;
                  default -> -1;
                })
        .description("Circuit breaker around the ODM RES call: 0 closed, 1 half open, 2 open")
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
    RuleDecision d = resCall.recordCallable(() -> res.execute(app));
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
                app.getEmployment().getAnnualIncome()),
            inputsOf(app),
            res.rulesetPath(),
            res.rulesetDeployedAt());
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
    // business counter; labels are coarse, never an identifier or anything about a person
    Counter.builder("decisions.total")
        .description("Automated decisions")
        .tag("outcome", d.outcome().name())
        .tag("product", d.product() == null ? "NONE" : d.product().name())
        .tag("province", app.getAddress().getProvince().name())
        .register(registry)
        .increment();
  }

  private DecisionInputs inputsOf(Application app) {
    var emp = app.getEmployment();
    var bureau = app.getBureau();
    return new DecisionInputs(
        app.getApplicant().getAgeYears(),
        app.getAddress().getProvince(),
        app.getApplicant().getResidencyStatus(),
        emp.getStatus(),
        emp.getAnnualIncome(),
        emp.getMonthsAtEmployer(),
        app.getMonthlyHousingCost(),
        bureau.getMonthlyDebtPayments(),
        Math.round(app.tdsRatio() * 1000.0) / 1000.0,
        bureau.getScore(),
        bureau.isActiveBankruptcy(),
        bureau.getDelinquencies(),
        bureau.getInquiriesLast6Months(),
        bureau.getFileAgeMonths(),
        bureau.getExistingCreditLimit(),
        app.getRequestedLimit(),
        app.getRequestedProduct(),
        ApplicantToken.of(tokenKey, app.getApplicant().getApplicantId()));
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
