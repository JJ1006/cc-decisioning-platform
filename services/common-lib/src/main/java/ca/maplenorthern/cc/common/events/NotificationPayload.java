package ca.maplenorthern.cc.common.events;

import java.util.List;

/**
 * Payload of cc.notifications.v1. recipientRef is an opaque id, never a postal or email address.
 */
public record NotificationPayload(
    String applicationId,
    NoticeType noticeType,
    Channel channel,
    String recipientRef,
    String subject,
    String body,
    List<String> reasonTexts) {

  /** Kind of notice. */
  public enum NoticeType {
    APPROVAL,
    ADVERSE_ACTION,
    REFERRAL
  }

  /** Delivery channel. */
  public enum Channel {
    EMAIL,
    MAIL
  }
}
