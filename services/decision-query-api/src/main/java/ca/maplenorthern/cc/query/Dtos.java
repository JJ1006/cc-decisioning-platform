package ca.maplenorthern.cc.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The API's response types. Elasticsearch documents never leave the service as they are. */
public final class Dtos {
  private Dtos() {}

  @Schema(
      description =
          "A decision on an application, from the rules engine (ODM) or an underwriter (MANUAL)")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Decision(
      String applicationId,
      @Schema(description = "Id of the decision event") String decisionId,
      String outcome,
      @Schema(allowableValues = {"ODM", "MANUAL"}) String decisionSource,
      BigDecimal creditLimit,
      String product,
      String aprTier,
      List<String> reasonCodes,
      String rulesetVersion,
      Instant decidedAt,
      String province,
      String ageBand,
      Integer bureauScore,
      String underwriterId,
      String note) {}

  public record DecisionPage(
      List<Decision> items,
      @Schema(description = "Pass as searchAfter to get the next page; absent on the last page")
          @JsonInclude(JsonInclude.Include.NON_NULL)
          String nextSearchAfter) {}

  @Schema(description = "A reason code with its applicant-facing text and its rank")
  public record Reason(
      String code,
      String text,
      @Schema(description = "1 = the most decisive reason (declines before referrals)") int rank) {}

  @Schema(description = "A rule that fired, from the rules catalog (docs/rules-catalog.md)")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FiredRule(
      String id,
      @Schema(description = "Rule package: eligibility, risk, limit, limitcap, product, reasons")
          String packageName,
      String intent,
      String action,
      List<String> reasonCodes) {}

  @Schema(description = "Where the decision came from")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Ruleset(
      String path,
      String version,
      @Schema(description = "When RES says the ruleset was deployed; absent when it did not say")
          Instant deployedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Explanation(
      String applicationId,
      String outcome,
      @Schema(allowableValues = {"ODM", "MANUAL"}) String decisionSource,
      String rulesetVersion,
      Ruleset ruleset,
      Instant decidedAt,
      List<String> firedRules,
      @Schema(description = "The fired rules with what the catalog says about each")
          List<FiredRule> firedRuleDetails,
      @Schema(description = "Reason codes, most decisive first") List<Reason> reasons,
      @Schema(
              description =
                  "Present when an underwriter decided after the rules sent the application to REFER")
          Decision manualDecision) {}

  public record Rate(String key, long total, long approved, double approvalRate) {}

  public record ApprovalRates(String groupBy, Rate overall, List<Rate> groups) {}

  public record DeclineReason(String code, String text, long count) {}

  public record DeclineReasons(long declined, List<DeclineReason> reasons) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FairnessGroup(
      String key,
      long total,
      double approvalRate,
      @Schema(description = "Group approval rate divided by the overall approval rate")
          Double disparateImpactRatio,
      @Schema(
              description =
                  "True when the ratio is below the 0.8 threshold and the group is large enough to say so")
          boolean flagged,
      @Schema(
              description =
                  "True when the group has fewer decisions than minGroupSize: its ratio is noise and is never flagged")
          boolean insufficientSample) {}

  public record Fairness(
      String by,
      double overallApprovalRate,
      double threshold,
      @Schema(description = "Groups smaller than this are marked insufficientSample")
          int minGroupSize,
      List<FairnessGroup> groups) {}

  @Schema(
      description =
          "The inputs the rules saw for an application: decision-relevant values and a pseudonym, no direct identifier")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DecisionInputsView(
      String applicationId,
      String decisionId,
      Instant decidedAt,
      @Schema(description = "HMAC of the applicant id: the same applicant, the same token")
          String applicantToken,
      JsonNode inputs) {}

  public record PrivacyErasure(
      String applicationId, long total, java.util.Map<String, Long> deleted, String auditEventId) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PrivacyExport(
      String applicationId,
      Instant exportedAt,
      @Schema(
              description =
                  "Everything stored about the application, by data set; personal data is masked as stored")
          java.util.Map<String, List<JsonNode>> data) {}

  public record Latency(long decisions, double p50, double p95, double p99) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DeadLetter(
      String eventId,
      String source,
      String originalTopic,
      String errorClass,
      String errorMessage,
      Instant failedAt,
      @Schema(description = "Masked or redacted, cut to 256 characters") String originalValue) {}

  public record DeadLetters(long total, List<DeadLetter> items) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record LogLine(
      Instant timestamp, String service, String level, String logger, String message) {}

  public record Logs(String correlationId, List<LogLine> lines) {}
}
