package ca.maplenorthern.cc.common.events;

import ca.maplenorthern.cc.xom.AprTier;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.Product;
import ca.maplenorthern.cc.xom.ReasonCode;
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
    ApplicantSummary applicantSummary) {}
