package ca.maplenorthern.cc.xom;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.Introspector;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class XomTest {

  private static final List<String> PROHIBITED =
      List.of("gender", "sex", "ethnic", "race", "marital", "religion");

  @Test
  void ageOfMajorityIs19InSevenProvincesAnd18Elsewhere() {
    List<Province> nineteen =
        List.of(
            Province.BC,
            Province.NB,
            Province.NL,
            Province.NS,
            Province.NT,
            Province.NU,
            Province.YT);
    for (Province p : Province.values()) {
      assertThat(p.getAgeOfMajority()).as(p.name()).isEqualTo(nineteen.contains(p) ? 19 : 18);
    }
  }

  @Test
  void tdsRatioIsDebtPlusHousingOverMonthlyIncome() {
    Application app = new Application();
    Employment emp = new Employment();
    emp.setAnnualIncome(new BigDecimal("60000")); // 5000 / month
    CreditBureauReport bureau = new CreditBureauReport();
    bureau.setMonthlyDebtPayments(new BigDecimal("500"));
    app.setEmployment(emp);
    app.setBureau(bureau);
    app.setMonthlyHousingCost(new BigDecimal("1500"));
    assertThat(app.tdsRatio()).isEqualTo(0.4);
  }

  @Test
  void tdsRatioIsHighSentinelWhenIncomeIsZero() {
    Application app = new Application();
    Employment emp = new Employment();
    emp.setAnnualIncome(BigDecimal.ZERO);
    app.setEmployment(emp);
    app.setBureau(new CreditBureauReport());
    assertThat(app.tdsRatio()).isEqualTo(9.99);
  }

  @Test
  void decisionDeduplicatesReasonCodesAndKeepsRuleOrder() {
    Decision d = new Decision();
    d.addReasonCode(ReasonCode.R_LOW_SCORE);
    d.addReasonCode(ReasonCode.R_LOW_SCORE);
    d.addFiredRule("b");
    d.addFiredRule("a");
    assertThat(d.getReasonCodes()).containsExactly(ReasonCode.R_LOW_SCORE);
    assertThat(d.getFiredRules()).containsExactly("b", "a");
    assertThat(d.getCreditLimit()).isEqualTo(BigDecimal.ZERO);
  }

  @Test
  void escalateOnlyRaisesSeverity() {
    Decision d = new Decision();
    d.escalate(Outcome.APPROVE);
    assertThat(d.getOutcome()).isEqualTo(Outcome.APPROVE);
    d.escalate(Outcome.DECLINE);
    d.escalate(Outcome.REFER);
    d.escalate(Outcome.APPROVE);
    assertThat(d.getOutcome()).isEqualTo(Outcome.DECLINE);
  }

  @Test
  void noPropertyReferencesAProhibitedGround() throws Exception {
    for (Class<?> c :
        List.of(
            Applicant.class,
            Address.class,
            Employment.class,
            CreditBureauReport.class,
            Application.class,
            Decision.class)) {
      Stream.of(Introspector.getBeanInfo(c).getPropertyDescriptors())
          .map(pd -> pd.getName().toLowerCase())
          .forEach(
              name ->
                  assertThat(PROHIBITED)
                      .as(c.getSimpleName() + "." + name)
                      .noneMatch(name::contains));
    }
  }
}
