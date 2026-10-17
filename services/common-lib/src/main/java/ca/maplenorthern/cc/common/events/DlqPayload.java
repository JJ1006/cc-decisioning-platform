package ca.maplenorthern.cc.common.events;

import java.time.Instant;

/** Payload of a dead-letter event: the original record plus failure details. */
public record DlqPayload(
    String originalTopic,
    int originalPartition,
    long originalOffset,
    String originalKey,
    String originalValue,
    String errorClass,
    String errorMessage,
    Instant failedAt,
    int attempts) {}
