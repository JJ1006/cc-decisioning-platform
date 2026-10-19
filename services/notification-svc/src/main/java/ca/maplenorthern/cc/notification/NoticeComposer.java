package ca.maplenorthern.cc.notification;

import ca.maplenorthern.cc.common.events.DecisionPayload;
import ca.maplenorthern.cc.common.events.NotificationPayload;
import ca.maplenorthern.cc.common.events.NotificationPayload.Channel;
import ca.maplenorthern.cc.common.events.NotificationPayload.NoticeType;
import ca.maplenorthern.cc.xom.Product;
import ca.maplenorthern.cc.xom.ReasonCode;
import java.util.List;

/**
 * Writes the customer notice for a decision. Adverse-action notices list the principal reasons in
 * plain language taken from {@link ReasonCode#getDescription()}, follow Financial Consumer Agency
 * of Canada (FCAC) plain-language disclosure practice, and never reveal rule names or scores. Maple
 * Northern Bank is fictitious and no notice is delivered anywhere.
 */
public final class NoticeComposer {
  private static final String SIGNATURE = "Maple Northern Bank (demo, fictitious)";

  private NoticeComposer() {}

  /**
   * @param d a decision event payload
   * @return the notice for the applicant
   */
  public static NotificationPayload compose(DecisionPayload d) {
    String recipient = "APP:" + d.applicationId();
    return switch (d.outcome()) {
      case APPROVE ->
          new NotificationPayload(
              d.applicationId(),
              NoticeType.APPROVAL,
              Channel.EMAIL,
              recipient,
              "Your Maple Northern Bank credit card is approved",
              "Good news. Your application "
                  + d.applicationId()
                  + " is approved for the "
                  + label(d.product())
                  + " with a credit limit of CAD "
                  + d.creditLimit().stripTrailingZeros().toPlainString()
                  + ". Your card agreement states the interest rate for your pricing tier ("
                  + (d.aprTier() == null ? "standard" : d.aprTier().name())
                  + ") and all fees. "
                  + SIGNATURE,
              List.of());
      case DECLINE ->
          new NotificationPayload(
              d.applicationId(),
              NoticeType.ADVERSE_ACTION,
              Channel.MAIL,
              recipient,
              "Your Maple Northern Bank credit card application",
              NoticeTemplates.adverseAction(
                  d.applicationId(), d.reasonCodes(), NoticeTemplates.Settings.DEMO),
              reasonTexts(d.reasonCodes()));
      case REFER ->
          new NotificationPayload(
              d.applicationId(),
              NoticeType.REFERRAL,
              Channel.EMAIL,
              recipient,
              "Your credit card application is under review",
              "Thank you for applying. An underwriter is reviewing application "
                  + d.applicationId()
                  + " and we will write to you with a decision. We may ask for more information. "
                  + SIGNATURE,
              reasonTexts(d.reasonCodes()));
    };
  }

  private static List<String> reasonTexts(List<ReasonCode> codes) {
    return codes == null
        ? List.of()
        : codes.stream()
            .filter(c -> c != ReasonCode.R_APPROVED_STANDARD)
            .map(ReasonCode::getDescription)
            .toList();
  }

  private static String label(Product p) {
    if (p == null) {
      return "Maple Northern card";
    }
    return switch (p) {
      case NO_FEE -> "No-Fee Card";
      case CASHBACK -> "Cashback Card";
      case TRAVEL_INFINITE -> "Travel Infinite Card";
    };
  }
}
