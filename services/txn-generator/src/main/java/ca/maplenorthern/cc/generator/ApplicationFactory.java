package ca.maplenorthern.cc.generator;

import ca.maplenorthern.cc.xom.Address;
import ca.maplenorthern.cc.xom.Applicant;
import ca.maplenorthern.cc.xom.Application;
import ca.maplenorthern.cc.xom.Channel;
import ca.maplenorthern.cc.xom.CreditBureauReport;
import ca.maplenorthern.cc.xom.Employment;
import ca.maplenorthern.cc.xom.EmploymentStatus;
import ca.maplenorthern.cc.xom.Product;
import ca.maplenorthern.cc.xom.Province;
import ca.maplenorthern.cc.xom.ResidencyStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.datafaker.Faker;

/**
 * Builds synthetic applications. About 10% are deliberate edge cases that sit on a rule boundary
 * (age of majority by province, debt service ratio bands, score bands, residency). All data is
 * fictitious: names come from Datafaker, SINs start with 9 (never issued as permanent numbers).
 */
public class ApplicationFactory {

  /** Share of generated applications that are boundary cases. */
  static final double EDGE_SHARE = 0.10;

  private static final Map<Province, Double> PROVINCE_WEIGHT = new EnumMap<>(Province.class);
  private static final Map<Province, String> POSTAL_FIRST = new EnumMap<>(Province.class);
  private static final String POSTAL_LETTERS = "ABCEGHJKLMNPRSTVXY";
  private static final double[] TDS_EDGES = {0.35, 0.42, 0.50, 0.60};
  private static final int[] SCORE_EDGES = {559, 560, 619, 620, 679, 680};
  private static final int[] LIMITS = {
    1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000, 50000
  };

  static {
    // share of the Canadian population, rounded
    PROVINCE_WEIGHT.put(Province.ON, 38.5);
    PROVINCE_WEIGHT.put(Province.QC, 22.0);
    PROVINCE_WEIGHT.put(Province.BC, 13.5);
    PROVINCE_WEIGHT.put(Province.AB, 12.0);
    PROVINCE_WEIGHT.put(Province.MB, 3.6);
    PROVINCE_WEIGHT.put(Province.SK, 3.1);
    PROVINCE_WEIGHT.put(Province.NS, 2.6);
    PROVINCE_WEIGHT.put(Province.NB, 2.1);
    PROVINCE_WEIGHT.put(Province.NL, 1.4);
    PROVINCE_WEIGHT.put(Province.PE, 0.4);
    PROVINCE_WEIGHT.put(Province.NT, 0.12);
    PROVINCE_WEIGHT.put(Province.YT, 0.11);
    PROVINCE_WEIGHT.put(Province.NU, 0.1);
    POSTAL_FIRST.put(Province.ON, "KLMNP");
    POSTAL_FIRST.put(Province.QC, "GHJ");
    POSTAL_FIRST.put(Province.BC, "V");
    POSTAL_FIRST.put(Province.AB, "T");
    POSTAL_FIRST.put(Province.MB, "R");
    POSTAL_FIRST.put(Province.SK, "S");
    POSTAL_FIRST.put(Province.NS, "B");
    POSTAL_FIRST.put(Province.NB, "E");
    POSTAL_FIRST.put(Province.NL, "A");
    POSTAL_FIRST.put(Province.PE, "C");
    POSTAL_FIRST.put(Province.NT, "X");
    POSTAL_FIRST.put(Province.NU, "X");
    POSTAL_FIRST.put(Province.YT, "Y");
  }

  private final Random random;
  private final Faker faker;
  private final LocalDate today;
  private long sequence;
  private boolean lastEdge;

  public ApplicationFactory(long seed, LocalDate today) {
    this.random = new Random(seed);
    this.faker = new Faker(Locale.of("en", "CA"), random);
    this.today = today;
  }

  /**
   * @return the next synthetic application; roughly one in ten is a boundary case
   */
  public Application next() {
    long n = ++sequence;
    boolean edge = random.nextDouble() < EDGE_SHARE;
    lastEdge = edge;
    Province province = weightedProvince();
    int majority = province.getAgeOfMajority();

    int age = clamp((int) Math.round(42 + random.nextGaussian() * 14), majority, 85);
    ResidencyStatus residency = residency();
    int score = clamp((int) Math.round(700 + random.nextGaussian() * 85), 300, 900);
    double tds = clamp(0.32 + random.nextGaussian() * 0.12, 0.02, 0.9);

    if (edge) {
      switch (random.nextInt(4)) {
        case 0 -> age = majority - random.nextInt(2); // one year short, or exactly of age
        case 1 ->
            tds = TDS_EDGES[random.nextInt(TDS_EDGES.length)] + (random.nextInt(3) - 1) * 0.01;
        case 2 ->
            residency =
                random.nextBoolean()
                    ? ResidencyStatus.NON_RESIDENT
                    : ResidencyStatus.WORK_PERMIT_EXPIRED;
        default -> score = SCORE_EDGES[random.nextInt(SCORE_EDGES.length)];
      }
    }

    boolean bankrupt = random.nextDouble() < 0.02;
    if (bankrupt) {
      score = Math.min(score, 520 + random.nextInt(40));
    }
    EmploymentStatus status = employmentStatus();
    BigDecimal income = income(status);
    double monthlyIncome = income.doubleValue() / 12.0;
    double totalDebt = tds * monthlyIncome;
    int housing = (int) (Math.round(totalDebt * 0.6 / 10.0) * 10);
    int debt = (int) Math.max(0, Math.round(totalDebt - housing));

    Applicant applicant = new Applicant();
    applicant.setApplicantId(String.format("CUS-%08d", n));
    applicant.setFirstName(faker.name().firstName());
    applicant.setLastName(faker.name().lastName());
    LocalDate dob = today.minusYears(age).minusDays(random.nextInt(364));
    applicant.setDateOfBirth(dob);
    applicant.setAgeYears(Period.between(dob, today).getYears());
    applicant.setSin("9" + String.format("%08d", random.nextInt(100_000_000)));
    applicant.setResidencyStatus(residency);

    Address address = new Address();
    address.setLine1(faker.address().streetAddress());
    address.setCity(faker.address().city());
    address.setProvince(province);
    address.setPostalCode(postalCode(province));

    Employment employment = new Employment();
    employment.setStatus(status);
    employment.setEmployer(
        status == EmploymentStatus.EMPLOYED || status == EmploymentStatus.SELF_EMPLOYED
            ? faker.company().name()
            : null);
    employment.setAnnualIncome(income);
    employment.setMonthsAtEmployer(
        status == EmploymentStatus.EMPLOYED ? 1 + random.nextInt(240) : 0);

    CreditBureauReport bureau = new CreditBureauReport();
    bureau.setScore(score);
    bureau.setMonthlyDebtPayments(BigDecimal.valueOf(debt));
    bureau.setExistingCreditLimit(
        BigDecimal.valueOf(
            Math.round(income.doubleValue() * random.nextDouble() * 0.4 / 500) * 500));
    bureau.setDelinquencies(score < 620 ? random.nextInt(5) : (random.nextDouble() < 0.05 ? 1 : 0));
    bureau.setInquiriesLast6Months(inquiries());
    bureau.setActiveBankruptcy(bankrupt);
    bureau.setFileAgeMonths(
        random.nextDouble() < 0.05 ? random.nextInt(6) : 6 + random.nextInt(354));

    Application app = new Application();
    app.setApplicationId(String.format("APP-%08d", n));
    app.setRequestedProduct(requestedProduct());
    app.setRequestedLimit(BigDecimal.valueOf(LIMITS[random.nextInt(LIMITS.length)]));
    app.setMonthlyHousingCost(BigDecimal.valueOf(housing));
    app.setChannel(Channel.values()[random.nextInt(Channel.values().length)]);
    app.setApplicant(applicant);
    app.setAddress(address);
    app.setEmployment(employment);
    app.setBureau(bureau);
    return app;
  }

  /**
   * @return whether the application returned by the last {@link #next()} is a deliberate boundary
   *     case
   */
  boolean lastWasEdge() {
    return lastEdge;
  }

  private Province weightedProvince() {
    double total = PROVINCE_WEIGHT.values().stream().mapToDouble(Double::doubleValue).sum();
    double r = random.nextDouble() * total;
    for (Map.Entry<Province, Double> e : PROVINCE_WEIGHT.entrySet()) {
      r -= e.getValue();
      if (r <= 0) {
        return e.getKey();
      }
    }
    return Province.ON;
  }

  private String postalCode(Province province) {
    String firsts = POSTAL_FIRST.get(province);
    StringBuilder sb = new StringBuilder();
    sb.append(firsts.charAt(random.nextInt(firsts.length())));
    sb.append(random.nextInt(10));
    sb.append(POSTAL_LETTERS.charAt(random.nextInt(POSTAL_LETTERS.length())));
    sb.append(' ');
    sb.append(random.nextInt(10));
    sb.append(POSTAL_LETTERS.charAt(random.nextInt(POSTAL_LETTERS.length())));
    sb.append(random.nextInt(10));
    return sb.toString();
  }

  private ResidencyStatus residency() {
    double r = random.nextDouble();
    if (r < 0.90) return ResidencyStatus.CITIZEN;
    if (r < 0.97) return ResidencyStatus.PERMANENT_RESIDENT;
    return ResidencyStatus.WORK_PERMIT_VALID;
  }

  private EmploymentStatus employmentStatus() {
    double r = random.nextDouble();
    if (r < 0.70) return EmploymentStatus.EMPLOYED;
    if (r < 0.80) return EmploymentStatus.SELF_EMPLOYED;
    if (r < 0.90) return EmploymentStatus.RETIRED;
    if (r < 0.95) return EmploymentStatus.STUDENT;
    return EmploymentStatus.UNEMPLOYED;
  }

  /** Log-normal around a 62k median, scaled down for non-working statuses. */
  private BigDecimal income(EmploymentStatus status) {
    double base = 62_000 * Math.exp(random.nextGaussian() * 0.55);
    double factor =
        switch (status) {
          case RETIRED -> 0.6;
          case STUDENT -> 0.3;
          case UNEMPLOYED -> 0.15;
          default -> 1.0;
        };
    long rounded = Math.round(clamp(base * factor, 6_000, 500_000) / 100.0) * 100;
    return BigDecimal.valueOf(rounded);
  }

  private Product requestedProduct() {
    double r = random.nextDouble();
    if (r < 0.40) return Product.NO_FEE;
    if (r < 0.75) return Product.CASHBACK;
    return Product.TRAVEL_INFINITE;
  }

  private int inquiries() {
    int n = 0;
    while (n < 9 && random.nextDouble() < 0.35) {
      n++;
    }
    return n;
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /**
   * @return a message value that is not valid JSON, to exercise the dead-letter path
   */
  public static String poisonValue(long n) {
    return "{\"poison\":" + n + ", \"payload\": this is not json";
  }

  /**
   * @return all provinces the factory can produce (for tests)
   */
  static List<Province> provinces() {
    return List.copyOf(PROVINCE_WEIGHT.keySet());
  }
}
