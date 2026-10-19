package ca.maplenorthern.cc.notification;

import static org.assertj.core.api.Assertions.assertThat;

import ca.maplenorthern.cc.xom.ReasonCode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The adverse-action notice is bilingual, complete, and has no placeholder left over. */
class NoticeTemplatesTest {

  @Test
  void everyReasonCodeHasAFrenchText() {
    for (ReasonCode c : ReasonCode.values()) {
      assertThat(NoticeTemplates.reasonFr(c)).as("French text of %s", c).isNotBlank();
    }
  }

  @Test
  void noticeHasBothLanguages_theReasonsInBoth_andNoPlaceholderLeft() {
    String body =
        NoticeTemplates.adverseAction(
            "APP-7",
            List.of(ReasonCode.R_HIGH_TDS, ReasonCode.R_DELINQUENCIES),
            NoticeTemplates.Settings.DEMO);
    assertThat(body)
        .contains("Dear applicant")
        .contains("Total debt service ratio is too high")
        .contains("Madame, Monsieur")
        .contains("Le ratio du service de la dette totale est trop élevé")
        .contains("Retards de paiement récents");
    assertThat(body).doesNotContain("{{").doesNotContain("}}");
  }

  @Test
  void noticeCoversWhatAdverseActionDisclosureNeeds() {
    String body =
        NoticeTemplates.adverseAction(
            "APP-8", List.of(ReasonCode.R_LOW_SCORE), NoticeTemplates.Settings.DEMO);
    // principal reasons, the credit bureau that was used, the right to a free report and to correct
    // it,
    // human review, a contact line and the regulator - in English and in French
    for (String needle :
        Arrays.asList(
            "principal reasons",
            "[credit bureau name]",
            "free copy of your credit report",
            "review of the decision by a person",
            "[Maple Northern Bank contact line]",
            "Financial Consumer Agency of Canada",
            "principaux motifs",
            "copie de votre dossier de crédit",
            "révisée par une personne",
            "Agence de la consommation en matière financière du Canada")) {
      assertThat(body).as(needle).contains(needle);
    }
  }

  @Test
  void approvedReasonIsNotListedAsAReasonForDecline() {
    String body =
        NoticeTemplates.adverseAction(
            "APP-9",
            List.of(ReasonCode.R_LOW_SCORE, ReasonCode.R_APPROVED_STANDARD),
            NoticeTemplates.Settings.DEMO);
    assertThat(body).doesNotContain("Meets standard approval criteria");
  }
}
