package ca.maplenorthern.cc.common.web;

import java.util.UUID;
import org.slf4j.MDC;

/** MDC helpers so every log line carries {@code correlationId}. */
public final class CorrelationContext {
  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  private CorrelationContext() {}

  /**
   * @return a new correlation id
   */
  public static String newId() {
    return UUID.randomUUID().toString();
  }

  /** Puts {@code id} (or a new id when blank) in the MDC and returns the id used. */
  public static String set(String id) {
    String effective = id == null || id.isBlank() ? newId() : id;
    MDC.put(MDC_KEY, effective);
    return effective;
  }

  /** Clears the correlation id from the MDC. */
  public static void clear() {
    MDC.remove(MDC_KEY);
  }
}
