package ca.maplenorthern.cc.common.events;

import ca.maplenorthern.cc.xom.Province;
import java.math.BigDecimal;

/** Non-identifying applicant attributes carried with a decision for analytics and fairness. */
public record ApplicantSummary(
    Province province, String ageBand, int bureauScore, double tdsRatio, BigDecimal annualIncome) {

  /**
   * @return the age band label used by dashboards, e.g. "35-44"
   */
  public static String ageBand(int ageYears) {
    if (ageYears < 25) return "18-24";
    if (ageYears < 35) return "25-34";
    if (ageYears < 45) return "35-44";
    if (ageYears < 55) return "45-54";
    if (ageYears < 65) return "55-64";
    return "65+";
  }
}
