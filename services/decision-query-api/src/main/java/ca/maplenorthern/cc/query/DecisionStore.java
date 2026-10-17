package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.web.ApiException;
import ca.maplenorthern.cc.query.Dtos.ApprovalRates;
import ca.maplenorthern.cc.query.Dtos.DeadLetter;
import ca.maplenorthern.cc.query.Dtos.DeadLetters;
import ca.maplenorthern.cc.query.Dtos.Decision;
import ca.maplenorthern.cc.query.Dtos.DecisionPage;
import ca.maplenorthern.cc.query.Dtos.DeclineReason;
import ca.maplenorthern.cc.query.Dtos.DeclineReasons;
import ca.maplenorthern.cc.query.Dtos.Fairness;
import ca.maplenorthern.cc.query.Dtos.FairnessGroup;
import ca.maplenorthern.cc.query.Dtos.Latency;
import ca.maplenorthern.cc.query.Dtos.LogLine;
import ca.maplenorthern.cc.query.Dtos.Logs;
import ca.maplenorthern.cc.query.Dtos.Rate;
import ca.maplenorthern.cc.query.Dtos.Reason;
import ca.maplenorthern.cc.xom.ReasonCode;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/**
 * All Elasticsearch access of the API. Reads the read aliases only; maps documents to DTOs. The
 * statistics count automated (ODM) decisions: they carry the applicant summary and measure the
 * rules, a manual decision answers a REFER.
 */
@Repository
public class DecisionStore {
  static final double FAIRNESS_THRESHOLD = 0.8;
  private static final String DECISIONS = "cc-decisions";
  private static final String DLQ = "cc-dlq";
  private static final String LOGS = "cc-logs";
  private static final String OUTCOME = "payload.outcome";
  private static final String SOURCE = "decisionSource";

  private final ElasticsearchClient es;

  public DecisionStore(ElasticsearchClient es) {
    this.es = es;
  }

  // ---------------------------------------------------------------- documents

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Doc(String eventId, Instant occurredAt, String decisionSource, Payload payload) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Payload(
      String applicationId,
      String outcome,
      BigDecimal creditLimit,
      String product,
      String aprTier,
      List<String> reasonCodes,
      List<String> firedRules,
      String rulesetVersion,
      Instant decidedAt,
      Summary applicantSummary,
      String underwriterId,
      String note) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Summary(String province, String ageBand, Integer bureauScore) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DlqDoc(
      String eventId,
      String source,
      String originalTopic,
      String errorClass,
      String errorMessage,
      Instant failedAt,
      String originalValue) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record LogDoc(
      @JsonProperty("@timestamp") Instant timestamp,
      String service,
      String level,
      String logger_name,
      String message) {}

  // ---------------------------------------------------------------- decisions

  /** A decision together with the rules that fired for it. */
  public record Detail(Decision decision, List<String> firedRules) {}

  /** The newest decision of the application, optionally only from one source. */
  public Optional<Decision> latest(String applicationId, String source) {
    return latestDetail(applicationId, source).map(Detail::decision);
  }

  public Optional<Detail> latestDetail(String applicationId, String source) {
    var resp =
        search(
            s ->
                s.index(DECISIONS)
                    .size(1)
                    .query(
                        bool(
                            b -> {
                              b.filter(term("payload.applicationId", applicationId));
                              if (source != null) {
                                b.filter(term(SOURCE, source));
                              }
                            }))
                    .sort(sort("occurredAt", SortOrder.Desc))
                    .sort(sort("eventId", SortOrder.Desc)),
            Doc.class);
    return resp.hits().hits().stream()
        .map(Hit::source)
        .map(d -> new Detail(toDecision(d), d.payload().firedRules()))
        .findFirst();
  }

  public DecisionPage list(
      String province,
      String outcome,
      Integer minScore,
      Integer maxScore,
      int size,
      String searchAfter) {
    List<FieldValue> after = decodeToken(searchAfter);
    var resp =
        search(
            s -> {
              s.index(DECISIONS)
                  .size(size + 1)
                  .query(
                      bool(
                          b -> {
                            if (province != null) {
                              b.filter(term("payload.applicantSummary.province", province));
                            }
                            if (outcome != null) {
                              b.filter(term(OUTCOME, outcome));
                            }
                            if (minScore != null || maxScore != null) {
                              b.filter(
                                  Query.of(
                                      q ->
                                          q.range(
                                              r ->
                                                  r.number(
                                                      n -> {
                                                        n.field(
                                                            "payload.applicantSummary.bureauScore");
                                                        if (minScore != null) {
                                                          n.gte(minScore.doubleValue());
                                                        }
                                                        if (maxScore != null) {
                                                          n.lte(maxScore.doubleValue());
                                                        }
                                                        return n;
                                                      }))));
                            }
                          }))
                  .sort(sort("occurredAt", SortOrder.Desc))
                  .sort(sort("eventId", SortOrder.Desc));
              if (!after.isEmpty()) {
                s.searchAfter(after);
              }
              return s;
            },
            Doc.class);
    List<Hit<Doc>> hits = resp.hits().hits();
    boolean more = hits.size() > size;
    List<Hit<Doc>> page = more ? hits.subList(0, size) : hits;
    String next = more ? encodeToken(page.get(page.size() - 1)) : null;
    return new DecisionPage(page.stream().map(h -> toDecision(h.source())).toList(), next);
  }

  private static Decision toDecision(Doc d) {
    Payload p = d.payload();
    Summary s = p.applicantSummary();
    return new Decision(
        p.applicationId(),
        d.eventId(),
        p.outcome(),
        d.decisionSource(),
        p.creditLimit(),
        p.product(),
        p.aprTier(),
        p.reasonCodes(),
        p.rulesetVersion(),
        p.decidedAt() != null ? p.decidedAt() : d.occurredAt(),
        s == null ? null : s.province(),
        s == null ? null : s.ageBand(),
        s == null ? null : s.bureauScore(),
        p.underwriterId(),
        p.note());
  }

  public static List<Reason> reasons(List<String> codes) {
    List<Reason> out = new ArrayList<>();
    for (String code : codes == null ? List.<String>of() : codes) {
      out.add(new Reason(code, reasonText(code)));
    }
    return out;
  }

  static String reasonText(String code) {
    try {
      return ReasonCode.valueOf(code).getDescription();
    } catch (IllegalArgumentException e) {
      return code; // a code newer than this service
    }
  }

  // ---------------------------------------------------------------- statistics

  /** Approval rate per group of automated decisions (approved / all decisions of the group). */
  public ApprovalRates approvalRates(String groupBy) {
    Groups g = groups(groupBy);
    return new ApprovalRates(
        groupBy,
        new Rate("overall", g.total, g.approved, rate(g.approved, g.total)),
        g.rows.stream()
            .map(r -> new Rate(r.key, r.total, r.approved, rate(r.approved, r.total)))
            .toList());
  }

  /** Approval rate per group against the overall rate (the four-fifths rule: flag below 0.8). */
  public Fairness fairness(String by) {
    Groups g = groups(by);
    double overall = rate(g.approved, g.total);
    List<FairnessGroup> out = new ArrayList<>();
    for (Row r : g.rows) {
      double groupRate = rate(r.approved, r.total);
      Double ratio = overall == 0 ? null : round(groupRate / overall);
      out.add(
          new FairnessGroup(
              r.key, r.total, groupRate, ratio, ratio != null && ratio < FAIRNESS_THRESHOLD));
    }
    return new Fairness(by, overall, FAIRNESS_THRESHOLD, out);
  }

  public DeclineReasons declineReasons(int top) {
    var resp =
        search(
            s ->
                s.index(DECISIONS)
                    .size(0)
                    .trackTotalHits(t -> t.enabled(true))
                    .query(
                        bool(b -> b.filter(term(SOURCE, "ODM")).filter(term(OUTCOME, "DECLINE"))))
                    .aggregations(
                        "reasons",
                        Aggregation.of(
                            a -> a.terms(t -> t.field("payload.reasonCodes").size(top)))),
            Void.class);
    List<DeclineReason> out = new ArrayList<>();
    for (var b : resp.aggregations().get("reasons").sterms().buckets().array()) {
      String code = b.key().stringValue();
      out.add(new DeclineReason(code, reasonText(code), b.docCount()));
    }
    return new DeclineReasons(resp.hits().total() == null ? 0 : resp.hits().total().value(), out);
  }

  public Latency latency() {
    var resp =
        search(
            s ->
                s.index(DECISIONS)
                    .size(0)
                    .trackTotalHits(t -> t.enabled(true))
                    .query(bool(b -> b.filter(term(SOURCE, "ODM"))))
                    .aggregations(
                        "latency",
                        Aggregation.of(
                            a ->
                                a.percentiles(
                                    p ->
                                        p.field("payload.processingMillis")
                                            .percents(50.0, 95.0, 99.0)))),
            Void.class);
    Aggregate agg = resp.aggregations().get("latency");
    var values = agg.tdigestPercentiles().values().keyed();
    long n = resp.hits().total() == null ? 0 : resp.hits().total().value();
    return new Latency(n, pct(values, "50.0"), pct(values, "95.0"), pct(values, "99.0"));
  }

  private static double pct(java.util.Map<String, String> values, String key) {
    try {
      double v = Double.parseDouble(values.get(key));
      return Double.isNaN(v) ? 0 : round(v);
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private record Row(String key, long total, long approved) {}

  private record Groups(long total, long approved, List<Row> rows) {}

  private Groups groups(String groupBy) {
    Aggregation byGroup =
        switch (groupBy) {
          case "province" -> terms("payload.applicantSummary.province");
          case "ageBand" -> terms("payload.applicantSummary.ageBand");
          case "day" ->
              Aggregation.of(
                  a ->
                      a.dateHistogram(
                              h ->
                                  h.field("occurredAt")
                                      .calendarInterval(CalendarInterval.Day)
                                      .format("yyyy-MM-dd")
                                      .minDocCount(1))
                          .aggregations("approved", approved()));
          default ->
              throw new ApiException(
                  HttpStatus.BAD_REQUEST, "Invalid parameter", "unknown groupBy " + groupBy);
        };
    var resp =
        search(
            s ->
                s.index(DECISIONS)
                    .size(0)
                    .trackTotalHits(t -> t.enabled(true))
                    .query(bool(b -> b.filter(term(SOURCE, "ODM"))))
                    .aggregations("groups", byGroup)
                    .aggregations("approved", approved()),
            Void.class);
    long total = resp.hits().total() == null ? 0 : resp.hits().total().value();
    long approved = resp.aggregations().get("approved").filter().docCount();
    List<Row> rows = new ArrayList<>();
    Aggregate groups = resp.aggregations().get("groups");
    if (groupBy.equals("day")) {
      for (var b : groups.dateHistogram().buckets().array()) {
        rows.add(
            new Row(
                b.keyAsString(),
                b.docCount(),
                b.aggregations().get("approved").filter().docCount()));
      }
    } else {
      for (var b : groups.sterms().buckets().array()) {
        rows.add(
            new Row(
                b.key().stringValue(),
                b.docCount(),
                b.aggregations().get("approved").filter().docCount()));
      }
    }
    return new Groups(total, approved, rows);
  }

  private static Aggregation terms(String field) {
    return Aggregation.of(
        a -> a.terms(t -> t.field(field).size(100)).aggregations("approved", approved()));
  }

  private static Aggregation approved() {
    return Aggregation.of(a -> a.filter(term(OUTCOME, "APPROVE")));
  }

  private static double rate(long part, long whole) {
    return whole == 0 ? 0 : round((double) part / whole);
  }

  private static double round(double v) {
    return Math.round(v * 10000.0) / 10000.0;
  }

  // ---------------------------------------------------------------- ops

  public DeadLetters deadLetters(String topic, Instant from, int size) {
    var resp =
        search(
            s ->
                s.index(DLQ)
                    .size(size)
                    .trackTotalHits(t -> t.enabled(true))
                    .query(
                        bool(
                            b -> {
                              if (topic != null) {
                                b.filter(term("originalTopic", topic));
                              }
                              if (from != null) {
                                b.filter(
                                    Query.of(
                                        q ->
                                            q.range(
                                                r ->
                                                    r.date(
                                                        d ->
                                                            d.field("failedAt")
                                                                .gte(from.toString())))));
                              }
                            }))
                    .sort(sort("failedAt", SortOrder.Desc)),
            DlqDoc.class);
    List<DeadLetter> items =
        resp.hits().hits().stream()
            .map(Hit::source)
            .map(
                d ->
                    new DeadLetter(
                        d.eventId(),
                        d.source(),
                        d.originalTopic(),
                        d.errorClass(),
                        d.errorMessage(),
                        d.failedAt(),
                        cut(d.originalValue(), 256)))
            .toList();
    return new DeadLetters(resp.hits().total() == null ? 0 : resp.hits().total().value(), items);
  }

  public Logs logs(String correlationId, int size) {
    var resp =
        search(
            s ->
                s.index(LOGS)
                    .size(size)
                    .query(bool(b -> b.filter(term("correlationId", correlationId))))
                    .sort(sort("@timestamp", SortOrder.Asc)),
            LogDoc.class);
    List<LogLine> lines =
        resp.hits().hits().stream()
            .map(Hit::source)
            .map(
                d ->
                    new LogLine(
                        d.timestamp(),
                        d.service(),
                        d.level(),
                        d.logger_name(),
                        cut(d.message(), 1000)))
            .toList();
    return new Logs(correlationId, lines);
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
  }

  // ---------------------------------------------------------------- plumbing

  private <T> SearchResponse<T> search(
      java.util.function.Function<SearchRequest.Builder, SearchRequest.Builder> fn, Class<T> type) {
    try {
      return es.search(s -> fn.apply(s), type);
    } catch (IOException | RuntimeException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Search unavailable",
          "Elasticsearch did not answer the query");
    }
  }

  private static Query bool(Consumer<BoolQuery.Builder> fn) {
    return Query.of(
        q ->
            q.bool(
                b -> {
                  fn.accept(b);
                  return b;
                }));
  }

  private static Query term(String field, String value) {
    return Query.of(q -> q.term(t -> t.field(field).value(value)));
  }

  private static co.elastic.clients.elasticsearch._types.SortOptions sort(
      String field, SortOrder order) {
    return co.elastic.clients.elasticsearch._types.SortOptions.of(
        s -> s.field(f -> f.field(field).order(order)));
  }

  private static String encodeToken(Hit<Doc> hit) {
    String raw = hit.sort().get(0).longValue() + "|" + hit.sort().get(1).stringValue();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static List<FieldValue> decodeToken(String token) {
    if (token == null || token.isBlank()) {
      return List.of();
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
      int bar = raw.indexOf('|');
      return List.of(
          FieldValue.of(Long.parseLong(raw.substring(0, bar))),
          FieldValue.of(raw.substring(bar + 1)));
    } catch (RuntimeException e) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "Invalid parameter",
          "searchAfter is not a token from a previous page");
    }
  }
}
