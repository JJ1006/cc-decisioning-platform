package ca.maplenorthern.cc.generator;

import static org.assertj.core.api.Assertions.assertThat;

import ca.maplenorthern.cc.xom.Application;
import ca.maplenorthern.cc.xom.Province;
import ca.maplenorthern.cc.xom.ResidencyStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ApplicationFactoryTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
  private static final Pattern POSTAL = Pattern.compile("[A-Z]\\d[A-Z] \\d[A-Z]\\d");

  private static List<Application> sample(int n) {
    ApplicationFactory f = new ApplicationFactory(7, TODAY);
    List<Application> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(f.next());
    }
    return out;
  }

  @Test
  void sameSeedGivesSameApplications() {
    ApplicationFactory a = new ApplicationFactory(1, TODAY);
    ApplicationFactory b = new ApplicationFactory(1, TODAY);
    for (int i = 0; i < 20; i++) {
      Application x = a.next();
      Application y = b.next();
      assertThat(x.getApplicant().getFirstName()).isEqualTo(y.getApplicant().getFirstName());
      assertThat(x.getEmployment().getAnnualIncome())
          .isEqualTo(y.getEmployment().getAnnualIncome());
    }
  }

  @Test
  void everyApplicationIsWellFormed() {
    for (Application app : sample(3000)) {
      assertThat(app.getAddress().getPostalCode()).matches(POSTAL);
      assertThat(app.getBureau().getScore()).isBetween(300, 900);
      assertThat(app.getEmployment().getAnnualIncome().signum()).isPositive();
      assertThat(app.getApplicant().getAgeYears()).isBetween(17, 85);
      assertThat(app.getRequestedLimit().signum()).isPositive();
      assertThat(app.getApplicant().getSin()).startsWith("9").hasSize(9);
    }
  }

  @Test
  void provincesFollowPopulationShare() {
    Map<Province, Integer> counts = new EnumMap<>(Province.class);
    sample(10_000).forEach(a -> counts.merge(a.getAddress().getProvince(), 1, Integer::sum));
    assertThat(counts.get(Province.ON)).isBetween(3400, 4300);
    assertThat(counts.get(Province.QC)).isBetween(1900, 2500);
    assertThat(counts.get(Province.ON)).isGreaterThan(counts.get(Province.BC));
    assertThat(counts.getOrDefault(Province.NU, 0)).isLessThan(60);
  }

  @Test
  void aboutTenPercentAreBoundaryCases() {
    ApplicationFactory f = new ApplicationFactory(11, TODAY);
    int edge = 0;
    for (int i = 0; i < 10_000; i++) {
      f.next();
      if (f.lastWasEdge()) {
        edge++;
      }
    }
    assertThat(edge).isBetween(900, 1100);
  }

  @Test
  void boundaryCasesReallyHitTheBoundaries() {
    ApplicationFactory f = new ApplicationFactory(13, TODAY);
    int underAge = 0;
    int atMajority = 0;
    int badResidency = 0;
    int scoreEdge = 0;
    for (int i = 0; i < 20_000; i++) {
      Application a = f.next();
      if (!f.lastWasEdge()) {
        continue;
      }
      int majority = a.getAddress().getProvince().getAgeOfMajority();
      if (a.getApplicant().getAgeYears() == majority - 1) underAge++;
      if (a.getApplicant().getAgeYears() == majority) atMajority++;
      ResidencyStatus r = a.getApplicant().getResidencyStatus();
      if (r == ResidencyStatus.NON_RESIDENT || r == ResidencyStatus.WORK_PERMIT_EXPIRED)
        badResidency++;
      int s = a.getBureau().getScore();
      if (s == 559 || s == 560 || s == 619 || s == 620 || s == 679 || s == 680) scoreEdge++;
    }
    assertThat(underAge).isGreaterThan(100);
    assertThat(atMajority).isGreaterThan(100);
    assertThat(badResidency).isGreaterThan(100);
    assertThat(scoreEdge).isGreaterThan(100);
  }

  @Test
  void bankruptcyIsRareAndHurtsTheScore() {
    List<Application> apps = sample(10_000);
    long bankrupt = apps.stream().filter(a -> a.getBureau().isActiveBankruptcy()).count();
    assertThat(bankrupt).isBetween(120L, 300L);
    assertThat(
            apps.stream()
                .filter(a -> a.getBureau().isActiveBankruptcy())
                .mapToInt(a -> a.getBureau().getScore())
                .max()
                .getAsInt())
        .isLessThanOrEqualTo(600);
  }

  @Test
  void poisonValueIsNotJson() {
    assertThat(ApplicationFactory.poisonValue(3)).contains("not json");
  }
}
