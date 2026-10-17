package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.web.ApiException;
import ca.maplenorthern.cc.query.Dtos.DeadLetters;
import ca.maplenorthern.cc.query.Dtos.Logs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/v1/ops", produces = "application/json")
@Tag(name = "Operations")
public class OpsController {
  private final DecisionStore store;

  public OpsController(DecisionStore store) {
    this.store = store;
  }

  @GetMapping("/dlq")
  @Operation(
      operationId = "browseDeadLetters",
      summary = "Browse dead-lettered events, newest first",
      description =
          "Filter by original topic and by failure time (from, ISO-8601). Values are the masked or redacted copies.")
  public DeadLetters dlq(
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) String from,
      @RequestParam(defaultValue = "20") int size) {
    if (size < 1 || size > DecisionsController.MAX_SIZE) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "Invalid parameter",
          "size must be 1 to " + DecisionsController.MAX_SIZE);
    }
    Instant since = null;
    if (from != null) {
      try {
        since = Instant.parse(from);
      } catch (DateTimeParseException e) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "Invalid parameter",
            "from must be an ISO-8601 instant, e.g. 2026-10-17T00:00:00Z");
      }
    }
    return store.deadLetters(topic, since, size);
  }

  @GetMapping("/logs")
  @Operation(
      operationId = "traceLogs",
      summary = "Log lines of one correlation id across services",
      description = "Oldest first, from the container logs shipped by Filebeat; at most 200 lines.")
  public Logs logs(@RequestParam String correlationId) {
    if (correlationId.isBlank() || correlationId.length() > 128) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Invalid parameter", "correlationId must be 1 to 128 characters");
    }
    return store.logs(correlationId, 200);
  }
}
