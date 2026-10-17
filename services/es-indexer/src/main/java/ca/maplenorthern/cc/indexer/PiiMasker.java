package ca.maplenorthern.cc.indexer;

import ca.maplenorthern.cc.common.events.ApplicantSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.Period;
import java.util.Iterator;
import java.util.Map;

/**
 * Removes personal data from any JSON before it is indexed, wherever it appears in the tree: SIN is
 * dropped, names become initials, a postal code keeps only its forward sortation area (FSA, first
 * three characters), a date of birth becomes an age band, and street lines are dropped. The input
 * is not modified.
 */
public final class PiiMasker {
  private PiiMasker() {}

  /**
   * @param node any JSON
   * @param today date used to turn a date of birth into an age band
   * @return a masked deep copy
   */
  public static JsonNode mask(JsonNode node, LocalDate today) {
    JsonNode copy = node.deepCopy();
    walk(copy, today);
    return copy;
  }

  private static void walk(JsonNode node, LocalDate today) {
    if (node instanceof ArrayNode array) {
      array.forEach(child -> walk(child, today));
    } else if (node instanceof ObjectNode obj) {
      for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
        walk(it.next().getValue(), today);
      }
      maskObject(obj, today);
    }
  }

  private static void maskObject(ObjectNode obj, LocalDate today) {
    obj.remove("sin");
    obj.remove("line1");
    obj.remove("employer");
    initials(obj, "firstName", "firstInitial");
    initials(obj, "lastName", "lastInitial");
    if (obj.has("postalCode") && obj.get("postalCode").isTextual()) {
      String pc = obj.get("postalCode").asText().replace(" ", "");
      obj.put("fsa", pc.length() >= 3 ? pc.substring(0, 3).toUpperCase() : "");
      obj.remove("postalCode");
    }
    if (obj.has("dateOfBirth") && obj.get("dateOfBirth").isTextual()) {
      try {
        int age =
            Period.between(LocalDate.parse(obj.get("dateOfBirth").asText()), today).getYears();
        obj.put("ageBand", ApplicantSummary.ageBand(age));
      } catch (RuntimeException e) {
        obj.put("ageBand", "unknown");
      }
      obj.remove("dateOfBirth");
    }
  }

  private static void initials(ObjectNode obj, String field, String replacement) {
    if (obj.has(field)) {
      String v = obj.get(field).asText("");
      obj.put(replacement, v.isBlank() ? "" : v.substring(0, 1).toUpperCase() + ".");
      obj.remove(field);
    }
  }
}
