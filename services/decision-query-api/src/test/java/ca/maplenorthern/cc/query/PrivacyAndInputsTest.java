package ca.maplenorthern.cc.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.web.Caller;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.RefreshRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The restricted decision inputs and the privacy operations, against a real Elasticsearch with the
 * real templates: who may read the inputs (roles), what an access request returns, and that an
 * erasure removes the application from every data set but leaves an audit record that does not
 * carry the application id.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PrivacyAndInputsTest {

  @Container
  static final ElasticsearchContainer ES =
      new ElasticsearchContainer(
              DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.23"))
          .withEnv("xpack.security.enabled", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("es.url", () -> "http://" + ES.getHttpHostAddress());
    r.add("es.username", () -> "");
    r.add("es.privacy-password", () -> "not-checked-without-security");
  }

  @Autowired MockMvc mvc;
  @Autowired ElasticsearchClient es;

  static final String TOKEN = "00000000000000000000000000000001";

  @BeforeAll
  static void noop() {}

  boolean loaded;

  synchronized void load() throws Exception {
    if (loaded) {
      return;
    }
    Path dir = Path.of(System.getProperty("repo.root"), "deploy/es");
    for (String type :
        List.of(
            "cc-decisions",
            "cc-decision-inputs",
            "cc-notifications",
            "cc-audit",
            "cc-dlq",
            "cc-logs")) {
      var json = Files.readString(dir.resolve(type + ".template.json"));
      es.indices().putIndexTemplate(t -> t.name(type).withJson(new java.io.StringReader(json)));
    }
    for (String id : List.of("P-1", "P-2")) {
      index("cc-decisions-2026.10.17", decision(id));
      index("cc-decision-inputs-000001", inputs(id));
      index("cc-notifications-2026.10.17", notification(id));
      index("cc-audit-2026.10.17", audit(id));
    }
    index("cc-logs-000001", log("decided P-1 as APPROVE"));
    es.indices().refresh(RefreshRequest.of(r -> r.index("cc-*")));
    loaded = true;
  }

  void index(String idx, Map<String, Object> doc) throws Exception {
    es.index(i -> i.index(idx).id((String) doc.get("eventId") + idx).document(doc));
  }

  static Map<String, Object> envelope(String id, String topic, Map<String, Object> payload) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("eventId", id);
    d.put("correlationId", "c-" + id);
    d.put("eventType", topic);
    d.put("schemaVersion", 1);
    d.put("occurredAt", "2026-10-17T14:00:00Z");
    d.put("@timestamp", "2026-10-17T14:00:00Z");
    d.put("producer", "test");
    d.put("topic", topic);
    d.put("payload", payload);
    return d;
  }

  static Map<String, Object> decision(String app) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("applicationId", app);
    p.put("outcome", "APPROVE");
    p.put("creditLimit", 5000);
    p.put("reasonCodes", List.of("R_APPROVED_STANDARD"));
    p.put("firedRules", List.of("RSN-01"));
    p.put("rulesetVersion", "1.0");
    p.put("rulesetPath", "/ccApprovalApp/1.0/ccApproval/1.0");
    p.put("rulesetDeployedAt", "2026-10-17T12:00:00Z");
    p.put("decidedAt", "2026-10-17T14:00:00Z");
    p.put("applicantSummary", Map.of("province", "ON", "ageBand", "35-44", "scoreBand", "740-900"));
    Map<String, Object> d = envelope("dec-" + app, "cc.decisions.approved.v1", p);
    d.put("decisionSource", "ODM");
    return d;
  }

  static Map<String, Object> inputs(String app) {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("age", 36);
    in.put("province", "ON");
    in.put("annualIncome", 85000);
    in.put("creditScore", 742);
    in.put("bankruptcyFlag", false);
    in.put("requestedLimit", 8000);
    in.put("requestedProduct", "CASHBACK");
    in.put("applicantToken", TOKEN);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("applicationId", app);
    p.put("applicantToken", TOKEN);
    p.put("inputs", in);
    Map<String, Object> d = envelope("inp-" + app, "cc.decisions.approved.v1", p);
    d.put("decisionSource", "ODM");
    return d;
  }

  static Map<String, Object> notification(String app) {
    return envelope(
        "not-" + app,
        "cc.notifications.v1",
        Map.of(
            "applicationId", app, "noticeType", "APPROVAL", "channel", "EMAIL", "subject", "ok"));
  }

  static Map<String, Object> audit(String app) {
    return envelope(
        "aud-" + app,
        "cc.audit.v1",
        Map.of(
            "actor", "decision-orchestrator",
            "action", "ODM_EXECUTE",
            "resourceType", "application",
            "resourceId", app,
            "result", "SUCCESS"));
  }

  static Map<String, Object> log(String message) {
    return Map.of(
        "@timestamp", "2026-10-17T14:00:01Z", "message", message, "service", "orchestrator");
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
    return request -> {
      request.setAttribute(Caller.ATTR, new Caller("apisix", "someone", role));
      return request;
    };
  }

  JsonNode json(org.springframework.test.web.servlet.ResultActions a) throws Exception {
    return Json.mapper().readTree(a.andReturn().getResponse().getContentAsString());
  }

  // ---------------------------------------------------------------- inputs

  @Test
  void underwritersAndTheAgentReadTheInputs_theAnalystRoleDoesNot() throws Exception {
    load();
    for (String role : List.of("UNDERWRITER", "AGENT")) {
      JsonNode v =
          json(
              mvc.perform(get("/v1/decisions/P-1/inputs").with(as(role)))
                  .andExpect(status().isOk()));
      assertThat(v.path("applicantToken").asText()).isEqualTo(TOKEN);
      assertThat(v.path("inputs").path("creditScore").asInt()).isEqualTo(742);
      assertThat(v.path("inputs").path("requestedLimit").asInt()).isEqualTo(8000);
    }
    for (String role : List.of("ANALYST", "PARTNER", "OPS", "SERVICE")) {
      mvc.perform(get("/v1/decisions/P-1/inputs").with(as(role))).andExpect(status().isForbidden());
    }
    mvc.perform(get("/v1/decisions/NOPE/inputs").with(as("AGENT")))
        .andExpect(status().isNotFound());
  }

  @Test
  void theInputsNeverCarryADirectIdentifier() throws Exception {
    load();
    String body =
        mvc.perform(get("/v1/decisions/P-1/inputs").with(as("UNDERWRITER")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .doesNotContain(
            "firstName", "lastName", "sin", "dateOfBirth", "postalCode", "employer", "line1");
  }

  // ---------------------------------------------------------------- privacy

  @Test
  void onlyOpsMayEraseOrExport() throws Exception {
    load();
    for (String role : List.of("ANALYST", "AGENT", "UNDERWRITER", "PARTNER")) {
      mvc.perform(get("/v1/ops/privacy/export/P-1").with(as(role)))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/v1/ops/privacy/erase")
                  .with(as(role))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"applicationId\":\"P-1\"}"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void exportReturnsEveryDataSetOfTheApplicationAndIsAudited() throws Exception {
    load();
    JsonNode x =
        json(
            mvc.perform(get("/v1/ops/privacy/export/P-2").with(as("OPS")))
                .andExpect(status().isOk()));
    assertThat(x.path("applicationId").asText()).isEqualTo("P-2");
    JsonNode data = x.path("data");
    assertThat(data.path("decisions")).hasSize(1);
    assertThat(data.path("decisionInputs")).hasSize(1);
    assertThat(data.path("notifications")).hasSize(1);
    assertThat(data.path("audit")).hasSize(1);
    assertThat(
            data.path("decisionInputs")
                .get(0)
                .path("payload")
                .path("inputs")
                .path("creditScore")
                .asInt())
        .isEqualTo(742);
    es.indices().refresh(RefreshRequest.of(r -> r.index("cc-audit*")));
    var audit =
        es.search(
            s ->
                s.index("cc-audit*")
                    .query(q -> q.term(t -> t.field("payload.action").value("PRIVACY_EXPORT"))),
            JsonNode.class);
    assertThat(audit.hits().hits()).isNotEmpty();
  }

  @Test
  void erasureRemovesTheApplicationEverywhere_andKeepsAnAuditRecordWithoutItsId() throws Exception {
    load();
    mvc.perform(
            post("/v1/ops/privacy/erase")
                .with(as("OPS"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicationId\":\"P-1\"}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.total").value(5)) // decision, inputs, notification, audit, the log line
        .andExpect(jsonPath("$.deleted.decisionInputs").value(1))
        .andExpect(jsonPath("$.deleted.logs").value(1));
    es.indices().refresh(RefreshRequest.of(r -> r.index("cc-*")));
    mvc.perform(get("/v1/decisions/P-1").with(as("ANALYST"))).andExpect(status().isNotFound());
    mvc.perform(get("/v1/decisions/P-1/inputs").with(as("AGENT"))).andExpect(status().isNotFound());
    // the other application is untouched
    mvc.perform(get("/v1/decisions/P-2/inputs").with(as("AGENT"))).andExpect(status().isOk());
    // the erasure itself is on record: who, what, how many - and a hash instead of the id
    var audit =
        es.search(
            s ->
                s.index("cc-audit*")
                    .query(q -> q.term(t -> t.field("payload.action").value("PRIVACY_ERASURE"))),
            JsonNode.class);
    assertThat(audit.hits().hits()).hasSize(1);
    String doc = audit.hits().hits().get(0).source().toString();
    assertThat(doc).contains("sha256:").contains("someone").doesNotContain("P-1\"");
    // the audit record does not bring the application back: it is not found by its id
    mvc.perform(get("/v1/ops/privacy/export/P-1").with(as("OPS")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.audit.length()").value(0));
  }

  @Test
  void badApplicationIdsAreRefused() throws Exception {
    mvc.perform(
            post("/v1/ops/privacy/erase")
                .with(as("OPS"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicationId\":\"*\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/v1/ops/privacy/erase")
                .with(as("OPS"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
