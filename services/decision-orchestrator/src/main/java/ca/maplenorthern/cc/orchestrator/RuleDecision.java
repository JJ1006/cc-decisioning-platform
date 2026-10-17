package ca.maplenorthern.cc.orchestrator;

import ca.maplenorthern.cc.xom.AprTier;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.Product;
import ca.maplenorthern.cc.xom.ReasonCode;
import java.math.BigDecimal;
import java.util.List;

/** The {@code decision} object returned by the ccApproval ruleset. */
public record RuleDecision(
    Outcome outcome,
    BigDecimal creditLimit,
    Product product,
    AprTier aprTier,
    List<ReasonCode> reasonCodes,
    List<String> firedRules) {

  public RuleDecision {
    creditLimit = creditLimit == null ? BigDecimal.ZERO : creditLimit;
    reasonCodes = reasonCodes == null ? List.of() : reasonCodes;
    firedRules = firedRules == null ? List.of() : firedRules;
  }
}
