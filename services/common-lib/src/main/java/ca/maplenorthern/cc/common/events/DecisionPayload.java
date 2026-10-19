package ca.maplenorthern.cc.common.events;

import ca.maplenorthern.cc.xom.AprTier;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.Product;
import ca.maplenorthern.cc.xom.ReasonCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Payload of cc.decisions.{approved,declined,referred}.v1. */
public record DecisionPayload(
    String applicationId,
    Outcome outcome,
    BigDecimal creditLimit,
    Product product,
    AprTier aprTier,
    List<ReasonCode> reasonCodes,
    List<String> firedRules,
    String rulesetVersion,
    Instant decidedAt,
    long processingMillis,
    ApplicantSummary applicantSummary,
    @JsonInclude(JsonInclude.Include.NON_NULL) DecisionInputs inputs,
    @JsonInclude(JsonInclude.Include.NON_NULL) String rulesetPath,
    @JsonInclude(JsonInclude.Include.NON_NULL) Instant rulesetDeployedAt) {

  /** Without the P12 fields (events written before them have none; consumers accept both). */
  public DecisionPayload(
      String applicationId,
      Outcome outcome,
      BigDecimal creditLimit,
      Product product,
      AprTier aprTier,
      List<ReasonCode> reasonCodes,
      List<String> firedRules,
      String rulesetVersion,
      Instant decidedAt,
      long processingMillis,
      ApplicantSummary applicantSummary) {
    this(
        applicationId,
        outcome,
        creditLimit,
        product,
        aprTier,
        reasonCodes,
        firedRules,
        rulesetVersion,
        decidedAt,
        processingMillis,
        applicantSummary,
        null,
        null,
        null);
  }
}
