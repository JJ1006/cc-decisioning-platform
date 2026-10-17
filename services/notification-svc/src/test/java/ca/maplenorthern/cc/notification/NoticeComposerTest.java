package ca.maplenorthern.cc.notification;

import static org.assertj.core.api.Assertions.assertThat;

import ca.maplenorthern.cc.common.events.DecisionPayload;
import ca.maplenorthern.cc.common.events.NotificationPayload.Channel;
import ca.maplenorthern.cc.common.events.NotificationPayload.NoticeType;
import ca.maplenorthern.cc.xom.AprTier;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.Product;
import ca.maplenorthern.cc.xom.ReasonCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoticeComposerTest {
  private static DecisionPayload decision(
      Outcome o, BigDecimal limit, Product p, AprTier t, ReasonCode... codes) {
    return new DecisionPayload(
        "APP-1",
        o,
        limit,
        p,
        t,
        List.of(codes),
        List.of("ELIG-01"),
        "1.0",
        Instant.now(),
        12,
        null);
  }

  @Test
  void approvalNamesProductAndLimit_withoutReasons() {
    var n =
        NoticeComposer.compose(
            decision(
                Outcome.APPROVE,
                new BigDecimal("8000.00"),
                Product.CASHBACK,
                AprTier.TIER_2,
                ReasonCode.R_APPROVED_STANDARD));
    assertThat(n.noticeType()).isEqualTo(NoticeType.APPROVAL);
    assertThat(n.channel()).isEqualTo(Channel.EMAIL);
    assertThat(n.body()).contains("Cashback Card").contains("CAD 8000").contains("TIER_2");
    assertThat(n.reasonTexts()).isEmpty();
  }

  @Test
  void adverseActionListsPlainLanguageReasons_andNoInternalNames() {
    var n =
        NoticeComposer.compose(
            decision(
                Outcome.DECLINE,
                BigDecimal.ZERO,
                null,
                null,
                ReasonCode.R_LOW_SCORE,
                ReasonCode.R_HIGH_TDS));
    assertThat(n.noticeType()).isEqualTo(NoticeType.ADVERSE_ACTION);
    assertThat(n.channel()).isEqualTo(Channel.MAIL);
    assertThat(n.reasonTexts())
        .containsExactly(
            "Credit bureau score is below the minimum", "Total debt service ratio is too high");
    assertThat(n.body()).contains("credit report").contains("Financial Consumer Agency of Canada");
    assertThat(n.body()).doesNotContain("ELIG").doesNotContain("R_LOW_SCORE");
  }

  @Test
  void referralPromisesAReview() {
    var n =
        NoticeComposer.compose(
            decision(Outcome.REFER, BigDecimal.ZERO, null, null, ReasonCode.R_THIN_FILE));
    assertThat(n.noticeType()).isEqualTo(NoticeType.REFERRAL);
    assertThat(n.subject()).contains("under review");
    assertThat(n.reasonTexts()).containsExactly("Credit file is too short to assess");
  }
}
