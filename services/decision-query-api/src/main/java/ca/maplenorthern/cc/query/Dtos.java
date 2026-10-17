package ca.maplenorthern.cc.query;

import com.fasterxml.jackson.annotation.JsonInclude;
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

  @Schema(description = "A reason code with its applicant-facing text")
  public record Reason(String code, String text) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Explanation(
      String applicationId,
      String outcome,
      String rulesetVersion,
      Instant decidedAt,
      List<String> firedRules,
      List<Reason> reasons,
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
      @Schema(description = "True when the ratio is below the 0.8 threshold") boolean flagged) {}

  public record Fairness(
      String by, double overallApprovalRate, double threshold, List<FairnessGroup> groups) {}

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
