package ca.maplenorthern.cc.common.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** Payload of cc.audit.v1. */
public record AuditPayload(
    String actor,
    String action,
    String resourceType,
    String resourceId,
    Result result,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details) {

  /** Outcome of the audited action. */
  public enum Result {
    SUCCESS,
    FAILURE,
    DENIED
  }
}
