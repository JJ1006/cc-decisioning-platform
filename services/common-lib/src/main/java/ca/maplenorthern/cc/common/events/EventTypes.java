package ca.maplenorthern.cc.common.events;

/** Event type names; equal to the topic that carries them. */
public final class EventTypes {
  public static final String SUBMITTED = "cc.applications.submitted.v1";
  public static final String APPROVED = "cc.decisions.approved.v1";
  public static final String DECLINED = "cc.decisions.declined.v1";
  public static final String REFERRED = "cc.decisions.referred.v1";
  public static final String MANUAL = "cc.decisions.manual.v1";
  public static final String NOTIFICATION = "cc.notifications.v1";
  public static final String AUDIT = "cc.audit.v1";
  public static final String DLQ = "cc.dlq.v1";

  private EventTypes() {}
}
