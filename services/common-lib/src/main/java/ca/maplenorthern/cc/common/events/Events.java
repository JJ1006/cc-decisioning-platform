package ca.maplenorthern.cc.common.events;

import ca.maplenorthern.cc.common.Json;
import com.fasterxml.jackson.core.JsonProcessingException;

/** Reads and writes {@link Envelope}s with the shared mapper. */
public final class Events {
  private Events() {}

  /**
   * @param json an envelope as JSON
   * @param payload payload type
   * @return the parsed envelope
   * @throws JsonProcessingException when the JSON is not a valid envelope (a poison message)
   */
  public static <T> Envelope<T> read(String json, Class<T> payload) throws JsonProcessingException {
    var type = Json.mapper().getTypeFactory().constructParametricType(Envelope.class, payload);
    Envelope<T> envelope = Json.mapper().readValue(json, type);
    if (envelope == null || envelope.payload() == null) {
      throw new IllegalArgumentException("envelope without payload");
    }
    return envelope;
  }

  /**
   * @return the envelope as JSON
   */
  public static String write(Envelope<?> envelope) {
    try {
      return Json.mapper().writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize " + envelope.eventType(), e);
    }
  }
}
