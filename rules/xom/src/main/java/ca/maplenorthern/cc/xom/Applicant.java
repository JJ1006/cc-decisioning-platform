package ca.maplenorthern.cc.xom;

/**
 * Credit card applicant. Contains no prohibited grounds (no gender, ethnicity, marital status,
 * religion).
 *
 * <p>Plain bean: ODM generates the BOM from the getters and setters.
 */
public class Applicant {
  private String applicantId;
  private String firstName;
  private String lastName;
  private java.time.LocalDate dateOfBirth;
  private int ageYears;
  private String sin;
  private ResidencyStatus residencyStatus;

  /**
   * @return unique applicant id
   */
  public String getApplicantId() {
    return applicantId;
  }

  /**
   * @param applicantId unique applicant id
   */
  public void setApplicantId(String applicantId) {
    this.applicantId = applicantId;
  }

  /**
   * @return given name (synthetic)
   */
  public String getFirstName() {
    return firstName;
  }

  /**
   * @param firstName given name (synthetic)
   */
  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  /**
   * @return family name (synthetic)
   */
  public String getLastName() {
    return lastName;
  }

  /**
   * @param lastName family name (synthetic)
   */
  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  /**
   * @return date of birth
   */
  public java.time.LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  /**
   * @param dateOfBirth date of birth
   */
  public void setDateOfBirth(java.time.LocalDate dateOfBirth) {
    this.dateOfBirth = dateOfBirth;
  }

  /**
   * @return age in whole years, derived by the orchestrator before rule execution
   */
  public int getAgeYears() {
    return ageYears;
  }

  /**
   * @param ageYears age in whole years, derived by the orchestrator before rule execution
   */
  public void setAgeYears(int ageYears) {
    this.ageYears = ageYears;
  }

  /**
   * @return synthetic social insurance number; masked before indexing
   */
  public String getSin() {
    return sin;
  }

  /**
   * @param sin synthetic social insurance number; masked before indexing
   */
  public void setSin(String sin) {
    this.sin = sin;
  }

  /**
   * @return residency status
   */
  public ResidencyStatus getResidencyStatus() {
    return residencyStatus;
  }

  /**
   * @param residencyStatus residency status
   */
  public void setResidencyStatus(ResidencyStatus residencyStatus) {
    this.residencyStatus = residencyStatus;
  }
}
