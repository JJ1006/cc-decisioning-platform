package ca.maplenorthern.cc.application;

import ca.maplenorthern.cc.application.EventPublisher.Out;
import ca.maplenorthern.cc.application.IdempotencyCache.Outcome;
import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.events.AuditPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.ManualDecisionPayload;
import ca.maplenorthern.cc.common.web.ApiException;
import ca.maplenorthern.cc.common.web.CorrelationContext;
import ca.maplenorthern.cc.xom.Application;
import ca.maplenorthern.cc.xom.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/v1/applications", produces = "application/json")
@Tag(name = "Applications")
public class ApplicationsController {
  private static final Logger LOG = LoggerFactory.getLogger(ApplicationsController.class);
  static final String PRODUCER = "application-api";
  static final String UNDERWRITER = "UNDERWRITER";

  private final SchemaValidator schemas;
  private final EventPublisher publisher;
  private final QueryClient query;
  private final IdempotencyCache idempotency;
  private final Validator validator;
  // applications accepted by this instance: status SUBMITTED until the decision is indexed
  private final Set<String> accepted =
      Collections.newSetFromMap(
          Collections.synchronizedMap(
              new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                  return size() > 10_000;
                }
              }));

  public ApplicationsController(
      SchemaValidator schemas,
      EventPublisher publisher,
      QueryClient query,
      IdempotencyCache idempotency,
      Validator validator) {
    this.schemas = schemas;
    this.publisher = publisher;
    this.query = query;
    this.idempotency = idempotency;
    this.validator = validator;
  }

  // ------------------------------------------------------------------ DTOs

  @Schema(description = "An accepted application: the decision follows asynchronously")
  public record Accepted(String applicationId, String status, String correlationId) {}

  @Schema(description = "Where the application stands")
  public record Status(
      String applicationId,
      @Schema(allowableValues = {"SUBMITTED", "APPROVED", "DECLINED", "REFERRED"}) String status,
      @Schema(allowableValues = {"ODM", "MANUAL"}) String decisionSource,
      BigDecimal creditLimit,
      String product,
      String decidedAt) {}

  @Schema(description = "An underwriter's decision on a REFERRED application")
  public record ManualDecisionRequest(
      @jakarta.validation.constraints.NotNull
          @Schema(description = "APPROVE or DECLINE; REFER is refused")
          ca.maplenorthern.cc.xom.Outcome outcome,
      @jakarta.validation.constraints.NotNull
          @jakarta.validation.constraints.PositiveOrZero
          @Schema(description = "CAD; positive for APPROVE, 0 for DECLINE")
          BigDecimal creditLimit,
      @Schema(description = "Required for APPROVE, absent for DECLINE") Product product,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500)
          String reason,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String underwriterId) {}

  @Schema(description = "The recorded manual decision")
  public record ManualAccepted(
      String applicationId, String decisionId, String status, String decisionSource) {}

  // ------------------------------------------------------------------ submit

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "submitApplication",
      summary = "Submit a credit card application",
      description =
          "The body is an application (see the schema). applicationId is optional: the server assigns one."
              + " The event is validated against the contract and published to cc.applications.submitted.v1."
              + " Send an Idempotency-Key to make retries safe.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content = @Content(schema = @Schema(implementation = Application.class))))
  @ApiResponse(responseCode = "202", description = "Accepted; Location points at the status")
  public ResponseEntity<Accepted> submit(
      @Parameter(description = "Makes the request idempotent for 24 hours (max 128 characters)")
          @RequestHeader(value = "Idempotency-Key", required = false)
          String idempotencyKey,
      @RequestBody JsonNode body) {
    checkKey(idempotencyKey);
    if (!body.isObject()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Invalid application", "the body must be a JSON object");
    }
    Outcome result =
        idempotency.execute(
            "submit", idempotencyKey, hash(body), () -> doSubmit((ObjectNode) body.deepCopy()));
    if (result.conflict()) {
      throw conflictingKey();
    }
    Accepted accepted = (Accepted) result.response();
    var response =
        ResponseEntity.accepted()
            .header("Location", "/v1/applications/" + accepted.applicationId() + "/status");
    if (result.replayed()) {
      response.header("Idempotent-Replayed", "true");
    }
    return response.body(accepted);
  }

  private Accepted doSubmit(ObjectNode application) {
    if (!application.hasNonNull("applicationId")) {
      application.put(
          "applicationId", "APP-" + UUID.randomUUID().toString().substring(0, 18).toUpperCase());
    }
    String id = application.get("applicationId").asText();
    String correlationId = MDC.get(CorrelationContext.MDC_KEY);
    var event =
        Envelope.of(
            EventTypes.SUBMITTED, correlationId, PRODUCER, Map.of("application", application));
    List<String> errors =
        schemas.validate("application-submitted", Json.mapper().valueToTree(event));
    if (!errors.isEmpty()) {
      throw new ApiException(
              HttpStatus.BAD_REQUEST,
              "Invalid application",
              "the application does not match the contract")
          .with("errors", errors);
    }
    publisher.publish(id, List.of(new Out(EventTypes.SUBMITTED, event)));
    accepted.add(id);
    LOG.info("application {} accepted", id);
    return new Accepted(id, "SUBMITTED", correlationId);
  }

  // ------------------------------------------------------------------ status

  @GetMapping("/{applicationId}/status")
  @Operation(
      operationId = "getApplicationStatus",
      summary = "Status of an application",
      description =
          "SUBMITTED until a decision is indexed, then APPROVED, DECLINED or REFERRED; after an underwriter decides it shows"
              + " that decision (decisionSource MANUAL). Read from decision-query-api.")
  public Status status(@PathVariable String applicationId) {
    return query
        .latestDecision(applicationId)
        .map(d -> toStatus(applicationId, d))
        .orElseGet(
            () -> {
              if (accepted.contains(applicationId)) {
                return new Status(applicationId, "SUBMITTED", null, null, null, null);
              }
              throw new ApiException(
                  HttpStatus.NOT_FOUND, "Application not found", "no application " + applicationId);
            });
  }

  static Status toStatus(String applicationId, JsonNode d) {
    String outcome = d.path("outcome").asText();
    String status =
        switch (outcome) {
          case "APPROVE" -> "APPROVED";
          case "DECLINE" -> "DECLINED";
          case "REFER" -> "REFERRED";
          default -> "SUBMITTED";
        };
    return new Status(
        applicationId,
        status,
        d.path("decisionSource").asText(null),
        d.hasNonNull("creditLimit") ? d.get("creditLimit").decimalValue() : null,
        d.path("product").asText(null),
        d.path("decidedAt").asText(null));
  }

  // ------------------------------------------------------------------ manual decision

  @PostMapping(
      path = "/{applicationId}/manual-decision",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "recordManualDecision",
      summary = "Underwriter decision on a REFERRED application",
      description =
          "Role UNDERWRITER only, from the X-Role header (placeholder until the gateway sets it, P6). The application must be"
              + " REFERRED. Publishes cc.decisions.manual.v1 and an audit event in one transaction. A caller with another role is"
              + " refused with 403 and the refusal is audited.")
  @ApiResponse(responseCode = "202", description = "Decision recorded")
  public ResponseEntity<ManualAccepted> manualDecision(
      @PathVariable String applicationId,
      @Parameter(description = "Caller role; must be UNDERWRITER")
          @RequestHeader(value = "X-Role", required = false)
          String role,
      @Parameter(description = "Makes the request idempotent for 24 hours (max 128 characters)")
          @RequestHeader(value = "Idempotency-Key", required = false)
          String idempotencyKey,
      @RequestBody ManualDecisionRequest request) {
    if (role == null || role.isBlank()) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "Role missing", "the X-Role header is required");
    }
    if (!UNDERWRITER.equals(role)) {
      audit(
          applicationId,
          "role:" + cut(role, 32),
          AuditPayload.Result.DENIED,
          Map.of("required", UNDERWRITER));
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "Role not allowed",
          "only the UNDERWRITER role can decide manually");
    }
    checkKey(idempotencyKey);
    checkRules(request);
    Outcome result =
        idempotency.execute(
            "manual:" + applicationId,
            idempotencyKey,
            hash(Json.mapper().valueToTree(request)),
            () -> doManual(applicationId, request));
    if (result.conflict()) {
      throw conflictingKey();
    }
    var response = ResponseEntity.accepted();
    if (result.replayed()) {
      response.header("Idempotent-Replayed", "true");
    }
    return response.body((ManualAccepted) result.response());
  }

  private void checkRules(ManualDecisionRequest r) {
    var violations = validator.validate(r);
    List<String> errors =
        new java.util.ArrayList<>(
            violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .toList());
    if (r.outcome() == ca.maplenorthern.cc.xom.Outcome.REFER) {
      errors.add("outcome must be APPROVE or DECLINE");
    } else if (r.outcome() == ca.maplenorthern.cc.xom.Outcome.APPROVE) {
      if (r.creditLimit() != null && r.creditLimit().signum() <= 0)
        errors.add("creditLimit must be positive for APPROVE");
      if (r.product() == null) errors.add("product is required for APPROVE");
    } else if (r.outcome() == ca.maplenorthern.cc.xom.Outcome.DECLINE) {
      if (r.creditLimit() != null && r.creditLimit().signum() != 0)
        errors.add("creditLimit must be 0 for DECLINE");
      if (r.product() != null) errors.add("product must be absent for DECLINE");
    }
    if (!errors.isEmpty()) {
      throw new ApiException(
              HttpStatus.BAD_REQUEST, "Invalid manual decision", "the request is not valid")
          .with("errors", errors);
    }
  }

  private ManualAccepted doManual(String applicationId, ManualDecisionRequest r) {
    JsonNode current =
        query
            .latestDecision(applicationId)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Application not found",
                        "no decision for application " + applicationId));
    String status = toStatus(applicationId, current).status();
    if (!"REFERRED".equals(status)) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "Not a referral",
          "application is " + status + "; only REFERRED applications can be decided manually");
    }
    String correlationId = MDC.get(CorrelationContext.MDC_KEY);
    UUID original = UUID.fromString(current.path("decisionId").asText());
    var payload =
        new ManualDecisionPayload(
            applicationId,
            r.underwriterId(),
            r.outcome(),
            r.creditLimit(),
            r.product(),
            r.reason(),
            original);
    var decision = Envelope.of(EventTypes.MANUAL, correlationId, PRODUCER, payload);
    var audit =
        Envelope.of(
            EventTypes.AUDIT,
            correlationId,
            PRODUCER,
            new AuditPayload(
                r.underwriterId(),
                "MANUAL_DECISION",
                "application",
                applicationId,
                AuditPayload.Result.SUCCESS,
                Map.of(
                    "outcome",
                    r.outcome().name(),
                    "creditLimit",
                    r.creditLimit(),
                    "decisionId",
                    decision.eventId().toString())));
    publisher.publish(
        applicationId,
        List.of(new Out(EventTypes.MANUAL, decision), new Out(EventTypes.AUDIT, audit)));
    LOG.info("manual decision {} for {} by {}", r.outcome(), applicationId, r.underwriterId());
    return new ManualAccepted(
        applicationId,
        decision.eventId().toString(),
        r.outcome() == ca.maplenorthern.cc.xom.Outcome.APPROVE ? "APPROVED" : "DECLINED",
        "MANUAL");
  }

  private void audit(
      String applicationId, String actor, AuditPayload.Result result, Map<String, Object> details) {
    try {
      var event =
          Envelope.of(
              EventTypes.AUDIT,
              MDC.get(CorrelationContext.MDC_KEY),
              PRODUCER,
              new AuditPayload(
                  actor, "MANUAL_DECISION", "application", applicationId, result, details));
      publisher.publish(applicationId, List.of(new Out(EventTypes.AUDIT, event)));
    } catch (ApiException e) {
      LOG.warn(
          "could not audit the refused request for {}", applicationId); // the refusal still stands
    }
  }

  // ------------------------------------------------------------------ helpers

  private static void checkKey(String key) {
    if (key != null && (key.isBlank() || key.length() > 128)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key", "the key must be 1 to 128 characters");
    }
  }

  private static ApiException conflictingKey() {
    return new ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Idempotency-Key reused",
        "this Idempotency-Key was used with a different request");
  }

  private static String hash(JsonNode node) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(node.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
