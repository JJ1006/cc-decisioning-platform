package ca.maplenorthern.cc.orchestrator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.ApplicationSubmittedPayload;
import ca.maplenorthern.cc.common.events.DecisionPayload;
import ca.maplenorthern.cc.common.events.DlqPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.Events;
import ca.maplenorthern.cc.xom.Address;
import ca.maplenorthern.cc.xom.Applicant;
import ca.maplenorthern.cc.xom.Application;
import ca.maplenorthern.cc.xom.CreditBureauReport;
import ca.maplenorthern.cc.xom.Employment;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.Product;
import ca.maplenorthern.cc.xom.Province;
import ca.maplenorthern.cc.xom.ResidencyStatus;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class OrchestratorFlowTest {
  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  static final WireMockServer RES =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String USER = "svc-" + java.util.UUID.randomUUID();
  static final String PASS = java.util.UUID.randomUUID().toString();
  static final String PATH = "/DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0";

  @BeforeAll
  static void startRes() {
    RES.start();
  }

  @AfterAll
  static void stopRes() {
    RES.stop();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("platform.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("res.base-url", () -> "http://localhost:" + RES.port());
    r.add("res.username", () -> USER);
    r.add("res.password", () -> PASS);
    r.add("res.retry-wait-ms", () -> "10");
    r.add("platform.kafka.max-retries", () -> "1");
  }

  @Autowired ResClient resClient;

  @BeforeEach
  void reset() {
    RES.resetAll();
    resClient.breaker().reset(); // the permanent-failure test opens the breaker on purpose
  }

  private static String decision(String outcome, int limit, String product, String reasons) {
    return "{\"__DecisionID__\":\"x\",\"decision\":{\"outcome\":\""
        + outcome
        + "\",\"creditLimit\":"
        + limit
        + ",\"product\":"
        + (product == null ? "null" : "\"" + product + "\"")
        + ",\"aprTier\":"
        + (limit > 0 ? "\"TIER_2\"" : "null")
        + ",\"reasonCodes\":["
        + reasons
        + "],\"firedRules\":[\"ELIG-01\",\"RSN-01\"]}}";
  }

  private static Application application(String id) {
    Applicant a = new Applicant();
    a.setApplicantId("CUS-" + id);
    a.setFirstName("Avery");
    a.setLastName("Tremblay");
    a.setDateOfBirth(LocalDate.of(1990, 5, 14));
    a.setAgeYears(36);
    a.setSin("900000001");
    a.setResidencyStatus(ResidencyStatus.CITIZEN);
    Address ad = new Address();
    ad.setProvince(Province.ON);
    ad.setPostalCode("M5V 2T6");
    Employment e = new Employment();
    e.setAnnualIncome(new BigDecimal("85000"));
    CreditBureauReport b = new CreditBureauReport();
    b.setScore(742);
    b.setMonthlyDebtPayments(new BigDecimal("650"));
    Application app = new Application();
    app.setApplicationId(id);
    app.setRequestedProduct(Product.CASHBACK);
    app.setRequestedLimit(new BigDecimal("8000"));
    app.setMonthlyHousingCost(new BigDecimal("1800"));
    app.setApplicant(a);
    app.setAddress(ad);
    app.setEmployment(e);
    app.setBureau(b);
    return app;
  }

  private static void send(String key, String value, String corr) {
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("key.serializer", StringSerializer.class.getName());
    p.put("value.serializer", StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      ProducerRecord<String, String> rec = new ProducerRecord<>(Topics.SUBMITTED, key, value);
      rec.headers().add("correlationId", corr.getBytes(StandardCharsets.UTF_8));
      producer.send(rec);
      producer.flush();
    }
  }

  private static String submitted(String id, String corr) {
    return Events.write(
        Envelope.of(
            EventTypes.SUBMITTED,
            corr,
            "txn-generator",
            new ApplicationSubmittedPayload(application(id))));
  }

  /** Reads committed records from one topic until {@code n} for the given key prefix arrive. */
  private static List<ConsumerRecord<String, String>> read(String topic, String keyPrefix, int n) {
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("group.id", "t-" + System.nanoTime());
    p.put("auto.offset.reset", "earliest");
    p.put("isolation.level", "read_committed");
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> got = new ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(topic));
      await()
          .atMost(Duration.ofSeconds(60))
          .until(
              () -> {
                got.clear();
                c.seekToBeginning(c.assignment());
                c.poll(Duration.ofMillis(500))
                    .forEach(
                        r -> {
                          if (r.key() != null && r.key().startsWith(keyPrefix)) got.add(r);
                        });
                return got.size() >= n;
              });
    }
    return got;
  }

  @Test
  void approvedApplicationIsRoutedAudited_andPiiNeverReachesRes() throws Exception {
    RES.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(decision("APPROVE", 8000, "CASHBACK", "\"R_APPROVED_STANDARD\""))));
    send("OK-1", submitted("OK-1", "corr-ok-1"), "corr-ok-1");

    var approved = read(Topics.APPROVED, "OK-1", 1).get(0);
    var env = Events.read(approved.value(), DecisionPayload.class);
    assertThat(env.correlationId()).isEqualTo("corr-ok-1");
    assertThat(env.producer()).isEqualTo("decision-orchestrator");
    DecisionPayload d = env.payload();
    assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
    assertThat(d.creditLimit()).isEqualByComparingTo("8000");
    assertThat(d.product()).isEqualTo(Product.CASHBACK);
    assertThat(d.firedRules()).containsExactly("ELIG-01", "RSN-01");
    assertThat(d.applicantSummary().ageBand()).isEqualTo("35-44");
    assertThat(d.applicantSummary().province()).isEqualTo(Province.ON);
    assertThat(d.processingMillis()).isGreaterThanOrEqualTo(0);
    assertThat(new String(approved.headers().lastHeader("correlationId").value()))
        .isEqualTo("corr-ok-1");

    var audit = read(Topics.AUDIT, "OK-1", 1).get(0);
    assertThat(Json.mapper().readTree(audit.value()).path("payload").path("action").asText())
        .isEqualTo("ODM_EXECUTE");

    RES.verify(
        postRequestedFor(urlEqualTo(PATH))
            .withHeader(
                "Authorization",
                equalTo(
                    "Basic "
                        + java.util.Base64.getEncoder()
                            .encodeToString((USER + ":" + PASS).getBytes())))
            .withRequestBody(matchingJsonPath("$.application.applicationId", equalTo("OK-1")))
            .withRequestBody(matchingJsonPath("$.application.bureau.score", equalTo("742"))));
    String sent = RES.getAllServeEvents().get(0).getRequest().getBodyAsString();
    assertThat(sent)
        .doesNotContain("900000001")
        .doesNotContain("dateOfBirth")
        .doesNotContain("\"sin\"");
  }

  @Test
  void declineAndReferGoToTheirOwnTopics() throws Exception {
    RES.stubFor(
        post(urlEqualTo(PATH))
            .inScenario("seq")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withBody(decision("DECLINE", 0, null, "\"R_LOW_SCORE\"")))
            .willSetStateTo("second"));
    RES.stubFor(
        post(urlEqualTo(PATH))
            .inScenario("seq")
            .whenScenarioStateIs("second")
            .willReturn(aResponse().withBody(decision("REFER", 0, null, "\"R_BORDERLINE_TDS\""))));
    send("RT-1", submitted("RT-1", "c1"), "c1");
    var declined = read(Topics.DECLINED, "RT-1", 1);
    assertThat(Events.read(declined.get(0).value(), DecisionPayload.class).payload().reasonCodes())
        .extracting(Enum::name)
        .containsExactly("R_LOW_SCORE");
    send("RT-2", submitted("RT-2", "c2"), "c2");
    var referred = read(Topics.REFERRED, "RT-2", 1);
    assertThat(Events.read(referred.get(0).value(), DecisionPayload.class).payload().outcome())
        .isEqualTo(Outcome.REFER);
  }

  @Test
  void poisonMessageLandsInDlqAndDoesNotBlockThePartition() throws Exception {
    RES.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withBody(decision("APPROVE", 5000, "NO_FEE", "\"R_APPROVED_STANDARD\""))));
    send("POISON-9", "{\"poison\": this is not json", "corr-p");
    send("AFTER-1", submitted("AFTER-1", "corr-after"), "corr-after");

    var dlq = read(Topics.dlq(Topics.SUBMITTED), "POISON-9", 1).get(0);
    var env = Events.read(dlq.value(), DlqPayload.class);
    assertThat(env.eventType()).isEqualTo(EventTypes.DLQ);
    assertThat(env.payload().originalTopic()).isEqualTo(Topics.SUBMITTED);
    assertThat(env.payload().originalValue()).contains("not json");
    assertThat(read(Topics.APPROVED, "AFTER-1", 1)).hasSize(1);
  }

  @Test
  void permanentRulesServerFailureIsDeadLettered_transientOneIsRetried() throws Exception {
    // transient: 503 once, then fine
    RES.stubFor(
        post(urlEqualTo(PATH))
            .inScenario("flaky")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("up"));
    RES.stubFor(
        post(urlEqualTo(PATH))
            .inScenario("flaky")
            .whenScenarioStateIs("up")
            .willReturn(
                aResponse()
                    .withBody(decision("APPROVE", 3000, "NO_FEE", "\"R_APPROVED_STANDARD\""))));
    send("FLAKY-1", submitted("FLAKY-1", "cf"), "cf");
    assertThat(read(Topics.APPROVED, "FLAKY-1", 1)).hasSize(1);

    // permanent: always 500
    RES.resetAll();
    RES.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
    send("DOWN-1", submitted("DOWN-1", "cd"), "cd");
    var dlq = read(Topics.dlq(Topics.SUBMITTED), "DOWN-1", 1).get(0);
    assertThat(Events.read(dlq.value(), DlqPayload.class).payload().errorClass())
        .contains("HttpServerErrorException");
  }
}
