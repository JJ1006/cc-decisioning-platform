package ca.maplenorthern.cc.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import java.util.List;
import org.springframework.stereotype.Component;

/** Validates events against the committed contracts (bundled into the jar from /contracts). */
@Component
public class SchemaValidator {
  private static final String ID = "https://maplenorthern.ca/schemas/";
  private final JsonSchemaFactory factory =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          b -> b.schemaMappers(m -> m.mapPrefix(ID, "classpath:contracts/")));

  /**
   * @param contract schema name without suffix, e.g. {@code application-submitted}
   * @return human-readable violations; empty when the event is valid
   */
  public List<String> validate(String contract, JsonNode event) {
    JsonSchema schema = factory.getSchema(SchemaLocation.of(ID + contract + ".schema.json"));
    return schema.validate(event).stream().map(m -> m.getMessage()).sorted().toList();
  }
}
