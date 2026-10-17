package ca.maplenorthern.cc.xom;

/**
 * Decision returned by the ccApproval ruleset, the output of rule execution.
 *
 * <p>Plain bean: ODM generates the BOM from the getters and setters.
 */
public class Decision {
  private Outcome outcome;
  private java.math.BigDecimal creditLimit = java.math.BigDecimal.ZERO;
  private Product product;
  private AprTier aprTier;
  private java.util.List<ReasonCode> reasonCodes = new java.util.ArrayList<>();
  private java.util.List<String> firedRules = new java.util.ArrayList<>();

  /**
   * @return APPROVE, DECLINE or REFER
   */
  public Outcome getOutcome() {
    return outcome;
  }

  /**
   * @param outcome APPROVE, DECLINE or REFER
   */
  public void setOutcome(Outcome outcome) {
    this.outcome = outcome;
  }

  /**
   * @return approved credit limit in CAD, zero unless approved
   */
  public java.math.BigDecimal getCreditLimit() {
    return creditLimit;
  }

  /**
   * @param creditLimit approved credit limit in CAD, zero unless approved
   */
  public void setCreditLimit(java.math.BigDecimal creditLimit) {
    this.creditLimit = creditLimit;
  }

  /**
   * @return approved product, null unless approved
   */
  public Product getProduct() {
    return product;
  }

  /**
   * @param product approved product, null unless approved
   */
  public void setProduct(Product product) {
    this.product = product;
  }

  /**
   * @return APR tier, null unless approved
   */
  public AprTier getAprTier() {
    return aprTier;
  }

  /**
   * @param aprTier APR tier, null unless approved
   */
  public void setAprTier(AprTier aprTier) {
    this.aprTier = aprTier;
  }

  /**
   * @return reason codes explaining the outcome
   */
  public java.util.List<ReasonCode> getReasonCodes() {
    return reasonCodes;
  }

  /**
   * @param reasonCodes reason codes explaining the outcome
   */
  public void setReasonCodes(java.util.List<ReasonCode> reasonCodes) {
    this.reasonCodes = reasonCodes;
  }

  /**
   * @return names of the rules that fired, in order
   */
  public java.util.List<String> getFiredRules() {
    return firedRules;
  }

  /**
   * @param firedRules names of the rules that fired, in order
   */
  public void setFiredRules(java.util.List<String> firedRules) {
    this.firedRules = firedRules;
  }

  /**
   * Records a reason code; duplicates are ignored.
   *
   * @param code the reason code
   */
  public void addReasonCode(ReasonCode code) {
    if (!reasonCodes.contains(code)) {
      reasonCodes.add(code);
    }
  }

  /**
   * Records that a rule fired.
   *
   * @param ruleName the rule name
   */
  public void addFiredRule(String ruleName) {
    firedRules.add(ruleName);
  }
}
