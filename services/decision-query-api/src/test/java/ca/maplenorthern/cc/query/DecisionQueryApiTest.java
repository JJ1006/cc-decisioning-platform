package ca.maplenorthern.cc.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.maplenorthern.cc.common.Json;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.RefreshRequest;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The API against a real Elasticsearch loaded with the real index templates and fixture documents
 * with known numbers: ON 10 decisions (8 approved), QC 10 (4 approved), BC 10 (6 approved), plus
 * one referral that an underwriter approved.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DecisionQueryApiTest {

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
  }

  @Autowired MockMvc mvc;
  @Autowired ElasticsearchClient es;
  static boolean loaded;

  static final Instant T0 = Instant.parse("2026-10-17T14:00:00Z");

  /** Templates from deploy/es, then the fixtures (once; the context is shared by the tests). */
  synchronized void load() throws Exception {
    if (loaded) {
      return;
    }
    Path dir = Path.of(System.getProperty("repo.root"), "deploy/es");
    for (String type : List.of("cc-decisions", "cc-dlq", "cc-logs")) {
      var json = Files.readString(dir.resolve(type + ".template.json"));
      es.indices().putIndexTemplate(t -> t.name(type).withJson(new java.io.StringReader(json)));
    }
    int n = 0;
    n = province("ON", 10, 8, 1, n); // 8 approve, 1 decline, 1 refer
    n = province("QC", 10, 4, 6, n); // 4 approve, 6 decline
    n = province("BC", 10, 6, 4, n); // 6 approve, 4 decline
    // a referral and the underwriter's decision on it
    index(
        "cc-decisions-2026.10.17",
        odm(
            "REF-1",
            "ON",
            "35-44",
            "REFER",
            640,
            List.of("R_BORDERLINE_SCORE"),
            List.of("RISK-DT-05"),
            50,
            n++));
    index("cc-decisions-2026.10.17", manual("REF-1", n++));
    // dead letters
    index("cc-dlq-000001", dlq("d1", "cc.applications.submitted.v1", "2026-10-17T14:05:00Z"));
    index("cc-dlq-000001", dlq("d2", "cc.applications.submitted.v1", "2026-10-17T14:10:00Z"));
    index("cc-dlq-000001", dlq("d3", "cc.decisions.approved.v1", "2026-10-17T14:20:00Z"));
    // logs of one correlation id on two services, and noise
    index("cc-logs-000001", log("orchestrator", "corr-1", "2026-10-17T14:00:02Z", "decided"));
    index("cc-logs-000001", log("generator", "corr-1", "2026-10-17T14:00:01Z", "sent"));
    index("cc-logs-000001", log("generator", "corr-2", "2026-10-17T14:00:03Z", "other"));
    es.indices().refresh(RefreshRequest.of(r -> r.index("cc-*")));
    loaded = true;
  }

  /**
   * @return next sequence number
   */
  int province(String province, int total, int approved, int declined, int n) throws Exception {
    String[] bands = {"25-34", "35-44", "45-54"};
    for (int i = 0; i < total; i++) {
      String outcome = i < approved ? "APPROVE" : i < approved + declined ? "DECLINE" : "REFER";
      List<String> reasons =
          switch (outcome) {
            case "APPROVE" -> List.of("R_APPROVED_STANDARD");
            case "DECLINE" ->
                i % 3 == 0 ? List.of("R_LOW_SCORE", "R_HIGH_TDS") : List.of("R_LOW_SCORE");
            default -> List.of("R_BORDERLINE_TDS");
          };
      int score = 500 + n * 3;
      index(
          "cc-decisions-2026.10.17",
          odm(
              province + "-" + i,
              province,
              bands[i % 3],
              outcome,
              score,
              reasons,
              List.of("RSN-01"),
              10 + n,
              n));
      n++;
    }
    return n;
  }

  Map<String, Object> odm(
      String app,
      String province,
      String ageBand,
      String outcome,
      int score,
      List<String> reasons,
      List<String> fired,
      long millis,
      int n) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("applicationId", app);
    payload.put("outcome", outcome);
    payload.put("creditLimit", outcome.equals("APPROVE") ? 5000 : 0);
    if (outcome.equals("APPROVE")) {
      payload.put("product", "CASHBACK");
      payload.put("aprTier", "TIER_2");
    }
    payload.put("reasonCodes", reasons);
    payload.put("firedRules", fired);
    payload.put("rulesetVersion", "ccApproval/1.0");
    payload.put("decidedAt", T0.plusSeconds(n).toString());
    payload.put("processingMillis", millis);
    payload.put(
        "applicantSummary",
        Map.of(
            "province",
            province,
            "ageBand",
            ageBand,
            "bureauScore",
            score,
            "tdsRatio",
            0.3,
            "annualIncome",
            80000));
    return envelope(
        "odm-" + app, "cc.decisions." + outcome.toLowerCase() + "d.v1", n, payload, "ODM");
  }

  Map<String, Object> manual(String app, int n) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("applicationId", app);
    payload.put("underwriterId", "UW-7");
    payload.put("outcome", "APPROVE");
    payload.put("creditLimit", 2500);
    payload.put("product", "NO_FEE");
    payload.put("note", "income verified");
    payload.put("originalDecisionEventId", "odm-" + app);
    return envelope("manual-" + app, "cc.decisions.manual.v1", n, payload, "MANUAL");
  }

  Map<String, Object> envelope(
      String id, String topic, int n, Map<String, Object> payload, String source) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("eventId", id);
    d.put("correlationId", "c-" + id);
    d.put("eventType", topic);
    d.put("schemaVersion", 1);
    d.put("occurredAt", T0.plusSeconds(n).toString());
    d.put("@timestamp", T0.plusSeconds(n).toString());
    d.put("producer", "test");
    d.put("topic", topic);
    d.put("payload", payload);
    d.put("decisionSource", source);
    return d;
  }

  Map<String, Object> dlq(String id, String topic, String failedAt) {
    return Map.of(
        "eventId",
        id,
        "@timestamp",
        failedAt,
        "source",
        "decision-orchestrator",
        "originalTopic",
        topic,
        "errorClass",
        "JsonParseException",
        "errorMessage",
        "bad json",
        "failedAt",
        failedAt,
        "originalValue",
        "x".repeat(400));
  }

  Map<String, Object> log(String service, String correlationId, String at, String message) {
    return Map.of(
        "@timestamp",
        at,
        "service",
        service,
        "correlationId",
        correlationId,
        "level",
        "INFO",
        "logger_name",
        "t",
        "message",
        message);
  }

  void index(String index, Map<String, Object> doc) throws Exception {
    es.index(
        i ->
            i.index(index)
                .document(
                    JsonData.of(
                        doc,
                        new co.elastic.clients.json.jackson.JacksonJsonpMapper(Json.mapper()))));
  }

  JsonNode json(String path) throws Exception {
    load();
    MvcResult r = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
    return Json.mapper().readTree(r.getResponse().getContentAsString());
  }

  // ------------------------------------------------------------------ tests

  @Test
  void decisionIsADtoNotTheDocument() throws Exception {
    load();
    mvc.perform(get("/v1/decisions/ON-0").header("X-Correlation-Id", "corr-x"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applicationId").value("ON-0"))
        .andExpect(jsonPath("$.outcome").value("APPROVE"))
        .andExpect(jsonPath("$.decisionSource").value("ODM"))
        .andExpect(jsonPath("$.province").value("ON"))
        .andExpect(jsonPath("$.payload").doesNotExist())
        .andExpect(jsonPath("$.eventId").doesNotExist())
        .andExpect(jsonPath("$['@timestamp']").doesNotExist());
  }

  @Test
  void unknownApplicationIsProblemJsonWithCorrelationId() throws Exception {
    load();
    mvc.perform(get("/v1/decisions/NOPE").header("X-Correlation-Id", "corr-404"))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Decision not found"))
        .andExpect(jsonPath("$.correlationId").value("corr-404"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("X-Correlation-Id", "corr-404"));
  }

  @Test
  void manualDecisionWinsOverTheReferralAndExplanationShowsBoth() throws Exception {
    JsonNode latest = json("/v1/decisions/REF-1");
    assertThat(latest.path("decisionSource").asText()).isEqualTo("MANUAL");
    assertThat(latest.path("outcome").asText()).isEqualTo("APPROVE");
    assertThat(latest.path("underwriterId").asText()).isEqualTo("UW-7");

    JsonNode explanation = json("/v1/decisions/REF-1/explanation");
    assertThat(explanation.path("outcome").asText()).isEqualTo("REFER");
    assertThat(explanation.path("rulesetVersion").asText()).isEqualTo("ccApproval/1.0");
    assertThat(explanation.path("firedRules").get(0).asText()).isEqualTo("RISK-DT-05");
    assertThat(explanation.path("reasons").get(0).path("code").asText())
        .isEqualTo("R_BORDERLINE_SCORE");
    assertThat(explanation.path("reasons").get(0).path("text").asText()).contains("borderline");
    assertThat(explanation.path("manualDecision").path("underwriterId").asText()).isEqualTo("UW-7");
  }

  @Test
  void filtersAndSearchAfterPaging() throws Exception {
    JsonNode qc = json("/v1/decisions?province=QC&outcome=DECLINE&size=100");
    assertThat(qc.path("items")).hasSize(6);
    assertThat(qc.has("nextSearchAfter")).isFalse();

    // walk all documents in pages of 7: no duplicates, no gaps, newest first
    Set<String> seen = new HashSet<>();
    List<String> order = new ArrayList<>();
    String token = null;
    int pages = 0;
    do {
      JsonNode page = json("/v1/decisions?size=7" + (token == null ? "" : "&searchAfter=" + token));
      page.path("items")
          .forEach(
              i -> {
                assertThat(seen.add(i.path("decisionId").asText())).as("no duplicates").isTrue();
                order.add(i.path("decidedAt").asText());
              });
      token = page.has("nextSearchAfter") ? page.path("nextSearchAfter").asText() : null;
      pages++;
    } while (token != null && pages < 20);
    assertThat(seen).hasSize(32); // 30 + referral + manual
    assertThat(pages).isEqualTo(5);

    JsonNode scores = json("/v1/decisions?minScore=500&maxScore=520&size=100");
    scores
        .path("items")
        .forEach(i -> assertThat(i.path("bureauScore").asInt()).isBetween(500, 520));
    assertThat(scores.path("items").size()).isPositive();
  }

  @Test
  void approvalRateOverallAndPerGroup() throws Exception {
    JsonNode rates = json("/v1/stats/approval-rate?groupBy=province");
    assertThat(rates.path("overall").path("total").asLong())
        .isEqualTo(31); // ODM only: 30 + the referral
    assertThat(rates.path("overall").path("approved").asLong()).isEqualTo(18);
    Map<String, Double> byProvince = new LinkedHashMap<>();
    rates
        .path("groups")
        .forEach(g -> byProvince.put(g.path("key").asText(), g.path("approvalRate").asDouble()));
    assertThat(byProvince.get("ON")).isEqualTo(0.7273); // 8 of 11 (the referral counts)
    assertThat(byProvince.get("QC")).isEqualTo(0.4);
    assertThat(byProvince.get("BC")).isEqualTo(0.6);

    JsonNode days = json("/v1/stats/approval-rate?groupBy=day");
    assertThat(days.path("groups")).hasSize(1);
    assertThat(days.path("groups").get(0).path("key").asText()).isEqualTo("2026-10-17");
  }

  @Test
  void fairnessRatioAgainstOverallAndFlag() throws Exception {
    JsonNode f = json("/v1/stats/fairness?by=province");
    double overall = 18.0 / 31.0;
    assertThat(f.path("overallApprovalRate").asDouble())
        .isEqualTo(Math.round(overall * 10000) / 10000.0);
    assertThat(f.path("threshold").asDouble()).isEqualTo(0.8);
    Map<String, JsonNode> groups = new LinkedHashMap<>();
    f.path("groups").forEach(g -> groups.put(g.path("key").asText(), g));
    // QC: 0.4 / 0.5806 = 0.6889 -> flagged; ON: 0.7273 / 0.5806 = 1.2525; BC: 0.6 / 0.5806 = 1.0333
    assertThat(groups.get("QC").path("disparateImpactRatio").asDouble()).isEqualTo(0.6889);
    assertThat(groups.get("QC").path("flagged").asBoolean()).isTrue();
    assertThat(groups.get("ON").path("flagged").asBoolean()).isFalse();
    assertThat(groups.get("BC").path("flagged").asBoolean()).isFalse();
    assertThat(groups.get("QC").path("total").asLong()).isEqualTo(10);

    JsonNode byAge = json("/v1/stats/fairness?by=ageBand");
    assertThat(byAge.path("groups").size()).isEqualTo(3);
  }

  @Test
  void declineReasonsAreCountedWithText() throws Exception {
    JsonNode r = json("/v1/stats/decline-reasons?top=2");
    // 11 declines (ON 1, QC 6, BC 4); two codes where the index is a multiple of 3: QC 6 and 9, BC
    // 6 and 9
    assertThat(r.path("declined").asLong()).isEqualTo(11);
    assertThat(r.path("reasons")).hasSize(2);
    assertThat(r.path("reasons").get(0).path("code").asText()).isEqualTo("R_LOW_SCORE");
    assertThat(r.path("reasons").get(0).path("count").asLong()).isEqualTo(11);
    assertThat(r.path("reasons").get(0).path("text").asText())
        .isEqualTo("Credit bureau score is below the minimum");
    assertThat(r.path("reasons").get(1).path("code").asText()).isEqualTo("R_HIGH_TDS");
    assertThat(r.path("reasons").get(1).path("count").asLong()).isEqualTo(4);
  }

  @Test
  void latencyPercentilesAreOrdered() throws Exception {
    JsonNode l = json("/v1/stats/latency");
    assertThat(l.path("decisions").asLong()).isEqualTo(31);
    assertThat(l.path("p50").asDouble()).isBetween(10.0, 60.0);
    assertThat(l.path("p95").asDouble()).isGreaterThanOrEqualTo(l.path("p50").asDouble());
    assertThat(l.path("p99").asDouble()).isGreaterThanOrEqualTo(l.path("p95").asDouble());
  }

  @Test
  void deadLettersFilterByTopicAndShortenValues() throws Exception {
    JsonNode all = json("/v1/ops/dlq?size=10");
    assertThat(all.path("total").asLong()).isEqualTo(3);
    assertThat(all.path("items").get(0).path("eventId").asText()).isEqualTo("d3"); // newest first
    JsonNode submitted = json("/v1/ops/dlq?topic=cc.applications.submitted.v1");
    assertThat(submitted.path("items")).hasSize(2);
    assertThat(submitted.path("items").get(0).path("originalValue").asText())
        .hasSize(259); // 256 + "..."
    JsonNode recent = json("/v1/ops/dlq?from=2026-10-17T14:15:00Z");
    assertThat(recent.path("items")).hasSize(1);
  }

  @Test
  void logsOfOneCorrelationIdAcrossServicesOldestFirst() throws Exception {
    JsonNode logs = json("/v1/ops/logs?correlationId=corr-1");
    assertThat(logs.path("lines")).hasSize(2);
    assertThat(logs.path("lines").get(0).path("service").asText()).isEqualTo("generator");
    assertThat(logs.path("lines").get(1).path("service").asText()).isEqualTo("orchestrator");
  }

  @Test
  void badInputIsAProblemNotAServerError() throws Exception {
    load();
    mvc.perform(get("/v1/decisions?size=101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid parameter"));
    mvc.perform(get("/v1/decisions?searchAfter=%25%25")).andExpect(status().isBadRequest());
    mvc.perform(get("/v1/decisions?province=XX")).andExpect(status().isBadRequest());
    mvc.perform(get("/v1/stats/fairness?by=income")).andExpect(status().isBadRequest());
    mvc.perform(get("/v1/stats/approval-rate?groupBy=income")).andExpect(status().isBadRequest());
    mvc.perform(get("/v1/stats/decline-reasons?top=0")).andExpect(status().isBadRequest());
    mvc.perform(get("/v1/ops/dlq?from=yesterday")).andExpect(status().isBadRequest());
  }
}
