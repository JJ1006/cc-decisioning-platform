package ca.maplenorthern.cc.xom;

/**
 * Credit card application, the input of the ccApproval ruleset.
 *
 * <p>Plain bean: ODM generates the BOM from the getters and setters.
 */
public class Application {
  private String applicationId;
  private Product requestedProduct;
  private java.math.BigDecimal requestedLimit;
  private java.math.BigDecimal monthlyHousingCost;
  private Channel channel;
  private Applicant applicant;
  private Address address;
  private Employment employment;
  private CreditBureauReport bureau;

  /**
   * @return unique application id
   */
  public String getApplicationId() {
    return applicationId;
  }

  /**
   * @param applicationId unique application id
   */
  public void setApplicationId(String applicationId) {
    this.applicationId = applicationId;
  }

  /**
   * @return product requested
   */
  public Product getRequestedProduct() {
    return requestedProduct;
  }

  /**
   * @param requestedProduct product requested
   */
  public void setRequestedProduct(Product requestedProduct) {
    this.requestedProduct = requestedProduct;
  }

  /**
   * @return credit limit requested in CAD
   */
  public java.math.BigDecimal getRequestedLimit() {
    return requestedLimit;
  }

  /**
   * @param requestedLimit credit limit requested in CAD
   */
  public void setRequestedLimit(java.math.BigDecimal requestedLimit) {
    this.requestedLimit = requestedLimit;
  }

  /**
   * @return monthly rent or mortgage in CAD
   */
  public java.math.BigDecimal getMonthlyHousingCost() {
    return monthlyHousingCost;
  }

  /**
   * @param monthlyHousingCost monthly rent or mortgage in CAD
   */
  public void setMonthlyHousingCost(java.math.BigDecimal monthlyHousingCost) {
    this.monthlyHousingCost = monthlyHousingCost;
  }

  /**
   * @return submission channel
   */
  public Channel getChannel() {
    return channel;
  }

  /**
   * @param channel submission channel
   */
  public void setChannel(Channel channel) {
    this.channel = channel;
  }

  /**
   * @return the applicant
   */
  public Applicant getApplicant() {
    return applicant;
  }

  /**
   * @param applicant the applicant
   */
  public void setApplicant(Applicant applicant) {
    this.applicant = applicant;
  }

  /**
   * @return residential address
   */
  public Address getAddress() {
    return address;
  }

  /**
   * @param address residential address
   */
  public void setAddress(Address address) {
    this.address = address;
  }

  /**
   * @return employment and income
   */
  public Employment getEmployment() {
    return employment;
  }

  /**
   * @param employment employment and income
   */
  public void setEmployment(Employment employment) {
    this.employment = employment;
  }

  /**
   * @return bureau report
   */
  public CreditBureauReport getBureau() {
    return bureau;
  }

  /**
   * @param bureau bureau report
   */
  public void setBureau(CreditBureauReport bureau) {
    this.bureau = bureau;
  }

  /**
   * Total debt service ratio: (monthly debt payments + monthly housing cost) divided by gross
   * monthly income.
   *
   * @return the ratio, or 9.99 when income is zero
   */
  public double tdsRatio() {
    if (employment == null || bureau == null || employment.getAnnualIncome() == null) {
      return 9.99;
    }
    double monthlyIncome = employment.getAnnualIncome().doubleValue() / 12.0;
    if (monthlyIncome <= 0) {
      return 9.99;
    }
    double debt = bureau.getMonthlyDebtPayments().doubleValue();
    double housing = monthlyHousingCost == null ? 0 : monthlyHousingCost.doubleValue();
    return (debt + housing) / monthlyIncome;
  }
}
