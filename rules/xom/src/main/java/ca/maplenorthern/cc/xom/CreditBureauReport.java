package ca.maplenorthern.cc.xom;

/**
 * Synthetic credit bureau report.
 *
 * <p>Plain bean: ODM generates the BOM from the getters and setters.
 */
public class CreditBureauReport {
  private int score;
  private java.math.BigDecimal monthlyDebtPayments;
  private java.math.BigDecimal existingCreditLimit;
  private int delinquencies;
  private int inquiriesLast6Months;
  private boolean activeBankruptcy;
  private int fileAgeMonths;

  /**
   * @return bureau score 300-900
   */
  public int getScore() {
    return score;
  }

  /**
   * @param score bureau score 300-900
   */
  public void setScore(int score) {
    this.score = score;
  }

  /**
   * @return total monthly debt payments in CAD, excluding housing
   */
  public java.math.BigDecimal getMonthlyDebtPayments() {
    return monthlyDebtPayments;
  }

  /**
   * @param monthlyDebtPayments total monthly debt payments in CAD, excluding housing
   */
  public void setMonthlyDebtPayments(java.math.BigDecimal monthlyDebtPayments) {
    this.monthlyDebtPayments = monthlyDebtPayments;
  }

  /**
   * @return sum of existing credit limits in CAD
   */
  public java.math.BigDecimal getExistingCreditLimit() {
    return existingCreditLimit;
  }

  /**
   * @param existingCreditLimit sum of existing credit limits in CAD
   */
  public void setExistingCreditLimit(java.math.BigDecimal existingCreditLimit) {
    this.existingCreditLimit = existingCreditLimit;
  }

  /**
   * @return number of delinquencies in the last 24 months
   */
  public int getDelinquencies() {
    return delinquencies;
  }

  /**
   * @param delinquencies number of delinquencies in the last 24 months
   */
  public void setDelinquencies(int delinquencies) {
    this.delinquencies = delinquencies;
  }

  /**
   * @return hard inquiries in the last 6 months
   */
  public int getInquiriesLast6Months() {
    return inquiriesLast6Months;
  }

  /**
   * @param inquiriesLast6Months hard inquiries in the last 6 months
   */
  public void setInquiriesLast6Months(int inquiriesLast6Months) {
    this.inquiriesLast6Months = inquiriesLast6Months;
  }

  /**
   * @return true when the applicant has an active bankruptcy
   */
  public boolean isActiveBankruptcy() {
    return activeBankruptcy;
  }

  /**
   * @param activeBankruptcy true when the applicant has an active bankruptcy
   */
  public void setActiveBankruptcy(boolean activeBankruptcy) {
    this.activeBankruptcy = activeBankruptcy;
  }

  /**
   * @return age of the oldest credit account in months
   */
  public int getFileAgeMonths() {
    return fileAgeMonths;
  }

  /**
   * @param fileAgeMonths age of the oldest credit account in months
   */
  public void setFileAgeMonths(int fileAgeMonths) {
    this.fileAgeMonths = fileAgeMonths;
  }
}
