package ca.maplenorthern.cc.xom;

/**
 * Applicant address.
 *
 * <p>Plain bean: ODM generates the BOM from the getters and setters.
 */
public class Address {
  private String line1;
  private String city;
  private Province province;
  private String postalCode;

  /**
   * @return street address
   */
  public String getLine1() {
    return line1;
  }

  /**
   * @param line1 street address
   */
  public void setLine1(String line1) {
    this.line1 = line1;
  }

  /**
   * @return city
   */
  public String getCity() {
    return city;
  }

  /**
   * @param city city
   */
  public void setCity(String city) {
    this.city = city;
  }

  /**
   * @return province or territory
   */
  public Province getProvince() {
    return province;
  }

  /**
   * @param province province or territory
   */
  public void setProvince(Province province) {
    this.province = province;
  }

  /**
   * @return postal code, format A1A 1A1
   */
  public String getPostalCode() {
    return postalCode;
  }

  /**
   * @param postalCode postal code, format A1A 1A1
   */
  public void setPostalCode(String postalCode) {
    this.postalCode = postalCode;
  }
}
