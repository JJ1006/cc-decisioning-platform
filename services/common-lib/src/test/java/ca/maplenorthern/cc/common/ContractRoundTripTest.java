package ca.maplenorthern.cc.common;

import static org.assertj.core.api.Assertions.assertThat;

import ca.maplenorthern.cc.common.events.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every example validates against its schema and survives DTO deserialize/serialize unchanged. */
class ContractRoundTripTest {
  private static final Path DIR = Path.of(System.getProperty("contracts.dir"));
  private static final String ID = "https://maplenorthern.ca/schemas/";
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          b ->
              b.schemaMappers(m -> m.mapPrefix(ID, DIR.toUri().toString().replaceAll("/?$", "/"))));

  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of(
            "application-submitted", new TypeReference<Envelope<ApplicationSubmittedPayload>>() {}),
        Arguments.of("decision-approved", new TypeReference<Envelope<DecisionPayload>>() {}),
        Arguments.of("decision-declined", new TypeReference<Envelope<DecisionPayload>>() {}),
        Arguments.of("decision-referred", new TypeReference<Envelope<DecisionPayload>>() {}),
        Arguments.of("decision-manual", new TypeReference<Envelope<ManualDecisionPayload>>() {}),
        Arguments.of("notification", new TypeReference<Envelope<NotificationPayload>>() {}),
        Arguments.of("audit", new TypeReference<Envelope<AuditPayload>>() {}),
        Arguments.of("dlq", new TypeReference<Envelope<DlqPayload>>() {}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void exampleRoundTripsThroughDto(String name, TypeReference<Envelope<?>> type) throws Exception {
    JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(ID + name + ".schema.json"));
    String original = Files.readString(DIR.resolve("examples/" + name + ".example.json"));

    assertThat(schema.validate(Json.mapper().readTree(original))).as("example vs schema").isEmpty();

    Envelope<?> dto = Json.mapper().readValue(original, type);
    // re-parse text so numeric node types (Int vs Decimal) compare equal
    JsonNode reserialized = Json.mapper().readTree(Json.mapper().writeValueAsString(dto));

    assertThat(reserialized).as("DTO round trip").isEqualTo(Json.mapper().readTree(original));
    assertThat(schema.validate(reserialized)).as("DTO output vs schema").isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void schemaRejectsUnknownEventType(String name, TypeReference<Envelope<?>> ignored)
      throws Exception {
    JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(ID + name + ".schema.json"));
    var node =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            Json.mapper()
                .readTree(Files.readString(DIR.resolve("examples/" + name + ".example.json")));
    node.put("eventType", "cc.something.else.v1");
    assertThat(schema.validate(node)).isNotEmpty();
  }
}
