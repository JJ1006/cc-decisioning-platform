package ca.maplenorthern.cc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.maplenorthern.cc.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Contract test with a real broker: what the API publishes is read back from Kafka and validated
 * against the committed JSON Schemas (not the copy the service validates with before it publishes).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SubmitContractTest {

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("platform.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("platform.kafka.keystore", () -> "");
  }

  @Autowired MockMvc mvc;
  @MockitoBean QueryClient query;

  static final Path REPO = Path.of(System.getProperty("repo.root"));
  static final String ID = "https://maplenorthern.ca/schemas/";
  static final JsonSchemaFactory SCHEMAS =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          b ->
              b.schemaMappers(
                  m ->
                      m.mapPrefix(
                          ID,
                          REPO.resolve("contracts").toUri().toString().replaceAll("/?$", "/"))));

  List<ConsumerRecord<String, String>> read(String topic, String key, int expected) {
    Properties p = new Properties();
    p.put("bootstrap.servers", KAFKA.getBootstrapServers());
    p.put("group.id", "contract-" + UUID.randomUUID());
    p.put("auto.offset.reset", "earliest");
    p.put("isolation.level", "read_committed");
    p.put("key.deserializer", StringDeserializer.class.getName());
    p.put("value.deserializer", StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> out = new java.util.ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(topic));
      long deadline = System.currentTimeMillis() + 30_000;
      while (out.size() < expected && System.currentTimeMillis() < deadline) {
        c.poll(Duration.ofMillis(500))
            .forEach(
                r -> {
                  if (key.equals(r.key())) {
                    out.add(r);
                  }
                });
      }
    }
    return out;
  }

  void assertValid(String contract, String json) throws Exception {
    var errors =
        SCHEMAS
            .getSchema(SchemaLocation.of(ID + contract + ".schema.json"))
            .validate(Json.mapper().readTree(json));
    assertThat(errors).as(contract).isEmpty();
  }

  @Test
  void postedApplicationBecomesAContractValidSubmittedEvent() throws Exception {
    JsonNode example =
        Json.mapper()
            .readTree(
                Files.readString(
                    REPO.resolve("contracts/examples/application-submitted.example.json")));
    ObjectNode application = (ObjectNode) example.path("payload").path("application").deepCopy();
    application.remove("applicationId"); // assigned by the server

    String response =
        mvc.perform(
                post("/v1/applications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", "contract-1")
                    .content(application.toString()))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = Json.mapper().readTree(response).path("applicationId").asText();

    var records = read("cc.applications.submitted.v1", id, 1);
    assertThat(records).hasSize(1);
    String value = records.get(0).value();
    assertValid("application-submitted", value);
    JsonNode event = Json.mapper().readTree(value);
    assertThat(event.path("correlationId").asText()).isEqualTo("contract-1");
    assertThat(event.path("producer").asText()).isEqualTo("application-api");
    assertThat(event.path("payload").path("application").path("applicationId").asText())
        .isEqualTo(id);
    assertThat(
            event.path("payload").path("application").path("applicant").path("lastName").asText())
        .isEqualTo("Tremblay");
  }

  @Test
  void manualDecisionPublishesContractValidDecisionAndAuditEvents() throws Exception {
    String original = UUID.randomUUID().toString();
    when(query.latestDecision("REF-C1"))
        .thenReturn(
            Optional.of(
                Json.mapper()
                    .readTree(
                        "{\"outcome\":\"REFER\",\"decisionSource\":\"ODM\",\"decisionId\":\""
                            + original
                            + "\"}")));

    mvc.perform(
            post("/v1/applications/REF-C1/manual-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .requestAttr(
                    ca.maplenorthern.cc.common.web.Caller.ATTR,
                    new ca.maplenorthern.cc.common.web.Caller(
                        "apisix", "underwriter-portal", "UNDERWRITER"))
                .header("X-Correlation-Id", "contract-2")
                .content(
                    "{\"outcome\":\"APPROVE\",\"creditLimit\":3000,\"product\":\"NO_FEE\",\"reason\":\"Verified income by phone\",\"underwriterId\":\"UW-77\"}"))
        .andExpect(status().isAccepted());

    var manual = read("cc.decisions.manual.v1", "REF-C1", 1);
    var audit = read("cc.audit.v1", "REF-C1", 1);
    assertThat(manual).hasSize(1);
    assertThat(audit).hasSize(1);
    assertValid("decision-manual", manual.get(0).value());
    assertValid("audit", audit.get(0).value());
    JsonNode m = Json.mapper().readTree(manual.get(0).value());
    assertThat(m.path("payload").path("originalDecisionEventId").asText()).isEqualTo(original);
    assertThat(m.path("payload").path("underwriterId").asText()).isEqualTo("UW-77");
    JsonNode a = Json.mapper().readTree(audit.get(0).value());
    assertThat(a.path("payload").path("details").path("decisionId").asText())
        .isEqualTo(m.path("eventId").asText());
    assertThat(a.path("correlationId").asText()).isEqualTo("contract-2");
  }
}
