package ca.maplenorthern.cc.xom;

/**
 * Applicant employment and income.
 *
 * <p>Plain bean: ODM generates the BOM from the getters and setters.
 */
public class Employment {
  private EmploymentStatus status;
  private String employer;
  private java.math.BigDecimal annualIncome;
  private int monthsAtEmployer;

  /**
   * @return employment status
   */
  public EmploymentStatus getStatus() {
    return status;
  }

  /**
   * @param status employment status
   */
  public void setStatus(EmploymentStatus status) {
    this.status = status;
  }

  /**
   * @return employer name (may be null)
   */
  public String getEmployer() {
    return employer;
  }

  /**
   * @param employer employer name (may be null)
   */
  public void setEmployer(String employer) {
    this.employer = employer;
  }

  /**
   * @return gross annual income in CAD
   */
  public java.math.BigDecimal getAnnualIncome() {
    return annualIncome;
  }

  /**
   * @param annualIncome gross annual income in CAD
   */
  public void setAnnualIncome(java.math.BigDecimal annualIncome) {
    this.annualIncome = annualIncome;
  }

  /**
   * @return months with current employer
   */
  public int getMonthsAtEmployer() {
    return monthsAtEmployer;
  }

  /**
   * @param monthsAtEmployer months with current employer
   */
  public void setMonthsAtEmployer(int monthsAtEmployer) {
    this.monthsAtEmployer = monthsAtEmployer;
  }
}
