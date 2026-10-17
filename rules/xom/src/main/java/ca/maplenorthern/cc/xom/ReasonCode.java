package ca.maplenorthern.cc.xom;

/** Reason code for adverse-action notices and explanations. Text is shown to applicants. */
public enum ReasonCode {
  R_AGE_BELOW_MAJORITY("Applicant is below the age of majority in their province"),
  R_NON_RESIDENT("Applicant is not a Canadian resident or permit holder"),
  R_ACTIVE_BANKRUPTCY("Applicant has an active bankruptcy"),
  R_LOW_SCORE("Credit bureau score is below the minimum"),
  R_HIGH_TDS("Total debt service ratio is too high"),
  R_INSUFFICIENT_INCOME("Income is below the minimum for this product"),
  R_DELINQUENCIES("Recent delinquencies on credit file"),
  R_EXCESSIVE_INQUIRIES("Too many recent credit inquiries"),
  R_THIN_FILE("Credit file is too short to assess"),
  R_BORDERLINE_SCORE("Credit score is borderline and requires manual review"),
  R_BORDERLINE_TDS("Debt service ratio is borderline and requires manual review"),
  R_APPROVED_STANDARD("Meets standard approval criteria");

  private final String description;

  ReasonCode(String description) {
    this.description = description;
  }

  /**
   * @return applicant-facing description
   */
  public String getDescription() {
    return description;
  }
}
