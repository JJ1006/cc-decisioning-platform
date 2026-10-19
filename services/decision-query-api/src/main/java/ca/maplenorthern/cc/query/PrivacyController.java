package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.web.ApiException;
import ca.maplenorthern.cc.query.Dtos.PrivacyErasure;
import ca.maplenorthern.cc.query.Dtos.PrivacyExport;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PIPEDA: the right to access and the right to erase (withdrawal of consent, end of retention), by
 * application. Operations role only. Every call writes an audit record whose resource id is a hash
 * of the application id, so the erasure record does not bring back what it erased.
 *
 * <p>Elasticsearch is erased here. The Kafka topics expire by retention (7 to 30 days); a
 * per-applicant encryption key (crypto-shredding) is the production answer, see
 * docs/privacy/data-classification.md.
 */
@RestController
@RequestMapping(path = "/v1/ops/privacy", produces = "application/json")
@Tag(name = "Privacy")
public class PrivacyController {
  /** data set name to index pattern (the privacy-ops role covers exactly these) */
  static final Map<String, String> DATA_SETS = new LinkedHashMap<>();

  static {
    DATA_SETS.put("decisions", "cc-decisions-*");
    DATA_SETS.put("decisionInputs", "cc-decision-inputs-*");
    DATA_SETS.put("notifications", "cc-notifications-*");
    DATA_SETS.put("audit", "cc-audit-*");
    DATA_SETS.put("deadLetters", "cc-dlq-*");
    DATA_SETS.put("logs", "cc-logs-*");
  }

  private final PrivacyEs privacy;

  public PrivacyController(PrivacyEs privacy) {
    this.privacy = privacy;
  }

  /**
   * @param applicationId the application whose data is erased
   */
  public record EraseRequest(String applicationId) {}

  @PostMapping("/erase")
  @Operation(
      operationId = "erasePrivacyData",
      summary = "Erase everything stored about an application in Elasticsearch",
      description =
          "Deletes decisions, decision inputs, notifications, audit records, dead letters and log lines that carry the"
              + " application id, and records PRIVACY_ERASURE in the audit trail (with a hash of the id). Role OPS.")
  public PrivacyErasure erase(@RequestBody EraseRequest body, HttpServletRequest request) {
    Roles.require(request, "OPS");
    String id = validId(body == null ? null : body.applicationId());
    var es = client();
    Map<String, Long> deleted = new LinkedHashMap<>();
    long total = 0;
    try {
      for (var set : DATA_SETS.entrySet()) {
        DeleteByQueryResponse r =
            es.deleteByQuery(
                d ->
                    d.index(set.getValue())
                        .query(matching(id))
                        .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));
        long n = r.deleted() == null ? 0 : r.deleted();
        deleted.put(set.getKey(), n);
        total += n;
      }
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "Search unavailable",
          "erasure was not completed: " + e.getMessage());
    }
    String auditId =
        audit(
            "PRIVACY_ERASURE",
            id,
            Roles.consumer(request),
            Map.of("deleted", total, "byDataSet", deleted));
    return new PrivacyErasure(id, total, deleted, auditId);
  }

  @GetMapping("/export/{applicationId}")
  @Operation(
      operationId = "exportPrivacyData",
      summary = "Everything stored about an application (access request)",
      description =
          "All documents that carry the application id, by data set, as stored (personal data is masked before it is stored)."
              + " Records PRIVACY_EXPORT in the audit trail. Role OPS.")
  public PrivacyExport export(@PathVariable String applicationId, HttpServletRequest request) {
    Roles.require(request, "OPS");
    String id = validId(applicationId);
    var es = client();
    Map<String, List<JsonNode>> data = new LinkedHashMap<>();
    long total = 0;
    try {
      for (var set : DATA_SETS.entrySet()) {
        var resp =
            es.search(s -> s.index(set.getValue()).size(100).query(matching(id)), ObjectNode.class);
        List<JsonNode> docs = new ArrayList<>();
        resp.hits().hits().forEach(h -> docs.add(h.source()));
        data.put(set.getKey(), docs);
        total += docs.size();
      }
    } catch (IOException e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "Search unavailable", e.getMessage());
    }
    audit("PRIVACY_EXPORT", id, Roles.consumer(request), Map.of("documents", total));
    return new PrivacyExport(id, Instant.now(), data);
  }

  /**
   * Every field that can carry an application id: decisions, inputs and notifications, audit, dead
   * letters (masked text), logs.
   */
  static Query matching(String applicationId) {
    return Query.of(
        q ->
            q.bool(
                b ->
                    b.minimumShouldMatch("1")
                        .should(
                            s -> s.term(t -> t.field("payload.applicationId").value(applicationId)))
                        .should(
                            s -> s.term(t -> t.field("payload.resourceId").value(applicationId)))
                        .should(s -> s.term(t -> t.field("originalKey").value(applicationId)))
                        .should(
                            s ->
                                s.wildcard(
                                    w -> w.field("originalValue").value("*" + applicationId + "*")))
                        .should(s -> s.matchPhrase(m -> m.field("message").query(applicationId)))));
  }

  private co.elastic.clients.elasticsearch.ElasticsearchClient client() {
    if (!privacy.configured()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Privacy operations are not configured",
          "the privacy-ops credential of this service is not set");
    }
    return privacy.client();
  }

  private static String validId(String id) {
    if (id == null || id.isBlank() || id.length() > 128 || !id.matches("[A-Za-z0-9._:-]+")) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "Invalid parameter",
          "applicationId must be 1 to 128 characters of A-Z a-z 0-9 . _ : -");
    }
    return id;
  }

  /** Audit record in the cc-audit index: the same document the services publish on cc.audit.v1. */
  private String audit(
      String action, String applicationId, String actor, Map<String, Object> details) {
    String eventId = UUID.randomUUID().toString();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("actor", actor);
    payload.put("action", action);
    payload.put("resourceType", "application");
    payload.put("resourceId", "sha256:" + sha256(applicationId).substring(0, 16));
    payload.put("result", "SUCCESS");
    payload.put("details", details);
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("eventId", eventId);
    doc.put("correlationId", "privacy-" + eventId);
    doc.put("eventType", "cc.audit.v1");
    doc.put("schemaVersion", 1);
    doc.put("occurredAt", Instant.now().toString());
    doc.put("producer", "decision-query-api");
    doc.put("topic", "direct");
    doc.put("@timestamp", Instant.now().toString());
    doc.put("payload", payload);
    try {
      privacy
          .client()
          .index(
              i ->
                  i.index("cc-audit-write")
                      .id(eventId)
                      .document(doc)
                      .opType(co.elastic.clients.elasticsearch._types.OpType.Create));
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "Audit unavailable",
          "the action was done but its audit record could not be written");
    }
    return eventId;
  }

  static String sha256(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : d) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static {
    Json.mapper(); // the shared mapper is loaded with the class, nothing to configure
  }
}
