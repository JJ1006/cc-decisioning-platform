package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.query.Dtos.FiredRule;
import ca.maplenorthern.cc.query.Dtos.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The rules of the ccApproval ruleset as the written catalog describes them (docs/rules-catalog.md,
 * turned into rules-catalog.json by scripts/gen-rules-catalog.py): what a fired rule id means, and
 * how decisive the rule behind a reason code is.
 */
@Component
public class RulesCatalog {
  private record Rule(
      String id,
      String pkg,
      String intent,
      String action,
      List<String> reasonCodes,
      int severity) {}

  private final Map<String, Rule> byId = new LinkedHashMap<>();

  public RulesCatalog() {
    try (InputStream in = getClass().getResourceAsStream("/rules-catalog.json")) {
      if (in == null) {
        throw new IllegalStateException(
            "rules-catalog.json is missing: run scripts/gen-rules-catalog.py");
      }
      for (JsonNode r : Json.mapper().readTree(in).path("rules")) {
        List<String> codes = new ArrayList<>();
        r.path("reasonCodes").forEach(c -> codes.add(c.asText()));
        byId.put(
            r.path("id").asText(),
            new Rule(
                r.path("id").asText(),
                r.path("package").asText(),
                r.path("intent").asText(),
                r.path("action").asText(),
                codes,
                r.path("severity").asInt()));
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot read rules-catalog.json", e);
    }
  }

  /** The fired rules with their catalog entries; an unknown id keeps only its id. */
  public List<FiredRule> describe(List<String> firedRuleIds) {
    List<FiredRule> out = new ArrayList<>();
    for (String id : firedRuleIds == null ? List.<String>of() : firedRuleIds) {
      Rule rule = byId.get(id);
      out.add(
          rule == null
              ? new FiredRule(id, null, null, null, null)
              : new FiredRule(rule.id, rule.pkg, rule.intent, rule.action, rule.reasonCodes));
    }
    return out;
  }

  /**
   * Reason codes, most decisive first: DECLINE reasons, then REFER reasons, then the approval
   * reason; inside a level in the order given.
   */
  public List<Reason> rank(List<String> codes) {
    List<String> sorted = new ArrayList<>(codes == null ? List.of() : codes);
    sorted.sort(Comparator.comparingInt((String c) -> -severityOf(c)));
    List<Reason> out = new ArrayList<>();
    int rank = 1;
    for (String code : sorted) {
      out.add(new Reason(code, DecisionStore.reasonText(code), rank++));
    }
    return out;
  }

  private int severityOf(String code) {
    // decision-table rules raise both DECLINE and REFER codes: the borderline codes and a thin
    // file refer, the approval code approves, every other code declines
    if (code.startsWith("R_BORDERLINE") || code.equals("R_THIN_FILE")) {
      return 2;
    }
    if (code.equals("R_APPROVED_STANDARD")) {
      return 1;
    }
    return byId.values().stream()
        .filter(rule -> rule.reasonCodes.contains(code))
        .mapToInt(rule -> rule.severity)
        .max()
        .orElse(3);
  }
}
