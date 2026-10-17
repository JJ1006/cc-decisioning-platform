package ca.maplenorthern.cc.common.events;

import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.Product;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;

/** Payload of cc.decisions.manual.v1: a human underwriter's decision on a REFER case. */
public record ManualDecisionPayload(
    String applicationId,
    String underwriterId,
    Outcome outcome,
    BigDecimal creditLimit,
    @JsonInclude(JsonInclude.Include.NON_NULL) Product product,
    String note,
    UUID originalDecisionEventId) {}
