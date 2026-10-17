package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.web.ApiException;
import ca.maplenorthern.cc.query.Dtos.Decision;
import ca.maplenorthern.cc.query.Dtos.DecisionPage;
import ca.maplenorthern.cc.query.Dtos.Explanation;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.Province;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/v1/decisions", produces = "application/json")
@Tag(name = "Decisions")
public class DecisionsController {
  static final int MAX_SIZE = 100;
  private final DecisionStore store;

  public DecisionsController(DecisionStore store) {
    this.store = store;
  }

  @GetMapping("/{applicationId}")
  @Operation(
      operationId = "getDecision",
      summary = "Latest decision of an application",
      description =
          "The newest decision, automated (ODM) or manual. 404 until Logstash has indexed it.")
  public Decision get(@PathVariable String applicationId) {
    return store.latest(applicationId, null).orElseThrow(() -> notFound(applicationId));
  }

  @GetMapping("/{applicationId}/explanation")
  @Operation(
      operationId = "explainDecision",
      summary = "Why the rules decided as they did",
      description =
          "Fired rules, reason codes with applicant-facing text and the ruleset version of the automated decision,"
              + " plus the underwriter's decision when there is one.")
  public Explanation explain(@PathVariable String applicationId) {
    var detail =
        store.latestDetail(applicationId, "ODM").orElseThrow(() -> notFound(applicationId));
    Decision odm = detail.decision();
    Decision manual = store.latest(applicationId, "MANUAL").orElse(null);
    return new Explanation(
        applicationId,
        odm.outcome(),
        odm.rulesetVersion(),
        odm.decidedAt(),
        detail.firedRules(),
        DecisionStore.reasons(odm.reasonCodes()),
        manual);
  }

  @GetMapping
  @Operation(
      operationId = "listDecisions",
      summary = "Search decisions, newest first",
      description =
          "Filters are optional. Page with searchAfter (the nextSearchAfter of the previous page); size is 1 to 100.")
  public DecisionPage list(
      @RequestParam(required = false) Province province,
      @RequestParam(required = false) Outcome outcome,
      @RequestParam(required = false) Integer minScore,
      @RequestParam(required = false) Integer maxScore,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String searchAfter) {
    if (size < 1 || size > MAX_SIZE) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Invalid parameter", "size must be 1 to " + MAX_SIZE);
    }
    if (minScore != null && maxScore != null && minScore > maxScore) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Invalid parameter", "minScore is above maxScore");
    }
    return store.list(
        province == null ? null : province.name(),
        outcome == null ? null : outcome.name(),
        minScore,
        maxScore,
        size,
        searchAfter);
  }

  private static ApiException notFound(String applicationId) {
    return new ApiException(
        HttpStatus.NOT_FOUND, "Decision not found", "no decision for application " + applicationId);
  }
}
