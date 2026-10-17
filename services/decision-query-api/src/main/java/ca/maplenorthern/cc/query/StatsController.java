package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.web.ApiException;
import ca.maplenorthern.cc.query.Dtos.ApprovalRates;
import ca.maplenorthern.cc.query.Dtos.DeclineReasons;
import ca.maplenorthern.cc.query.Dtos.Fairness;
import ca.maplenorthern.cc.query.Dtos.Latency;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Statistics over automated (ODM) decisions. */
@RestController
@RequestMapping(path = "/v1/stats", produces = "application/json")
@Tag(name = "Statistics")
public class StatsController {
  private final DecisionStore store;

  public StatsController(DecisionStore store) {
    this.store = store;
  }

  @GetMapping("/approval-rate")
  @Operation(
      operationId = "approvalRate",
      summary = "Approval rate by province, age band or day",
      description = "Approved divided by all automated decisions, overall and per group.")
  public ApprovalRates approvalRate(
      @RequestParam(defaultValue = "province")
          @Schema(allowableValues = {"province", "ageBand", "day"})
          String groupBy) {
    return store.approvalRates(groupBy);
  }

  @GetMapping("/decline-reasons")
  @Operation(
      operationId = "declineReasons",
      summary = "Most frequent decline reasons",
      description =
          "Reason codes of automated declines with applicant-facing text, most frequent first; top is 1 to 50.")
  public DeclineReasons declineReasons(@RequestParam(defaultValue = "10") int top) {
    if (top < 1 || top > 50) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid parameter", "top must be 1 to 50");
    }
    return store.declineReasons(top);
  }

  @GetMapping("/fairness")
  @Operation(
      operationId = "fairness",
      summary = "Disparate impact by province or age band",
      description =
          "Approval rate per group and the ratio to the overall approval rate; a group is flagged when the ratio is below 0.8"
              + " (the four-fifths rule). Small groups give noisy ratios: read total with it.")
  public Fairness fairness(
      @RequestParam(defaultValue = "province") @Schema(allowableValues = {"province", "ageBand"})
          String by) {
    if (!by.equals("province") && !by.equals("ageBand")) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Invalid parameter", "by must be province or ageBand");
    }
    return store.fairness(by);
  }

  @GetMapping("/latency")
  @Operation(
      operationId = "decisionLatency",
      summary = "Decision latency percentiles",
      description =
          "p50, p95 and p99 of the rules call (decisionLatencyMs, from processingMillis) over automated decisions.")
  public Latency latency() {
    return store.latency();
  }
}
