package ca.maplenorthern.cc.common.events;

import ca.maplenorthern.cc.xom.Application;

/** Payload of cc.applications.submitted.v1. */
public record ApplicationSubmittedPayload(Application application) {}
