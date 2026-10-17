package ca.maplenorthern.cc.xom;

/**
 * Canadian province or territory. The age of majority differs by province (19 in BC, NB, NL, NS,
 * NT, NU, YT; 18 elsewhere).
 */
public enum Province {
  AB(18),
  BC(19),
  MB(18),
  NB(19),
  NL(19),
  NS(19),
  NT(19),
  NU(19),
  ON(18),
  PE(18),
  QC(18),
  SK(18),
  YT(19);

  private final int ageOfMajority;

  Province(int ageOfMajority) {
    this.ageOfMajority = ageOfMajority;
  }

  /**
   * @return minimum age to enter a credit contract in this province
   */
  public int getAgeOfMajority() {
    return ageOfMajority;
  }
}
