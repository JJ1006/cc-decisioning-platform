package ca.maplenorthern.cc.common.events;

import ca.maplenorthern.cc.xom.EmploymentStatus;
import ca.maplenorthern.cc.xom.Product;
import ca.maplenorthern.cc.xom.Province;
import ca.maplenorthern.cc.xom.ResidencyStatus;
import java.math.BigDecimal;

/**
 * What the rules saw: the decision-relevant inputs of an application, with no direct identifier (no
 * name, SIN, date of birth, address line, postal code or employer). {@code applicantToken} links
 * the decisions of one applicant without revealing who: an HMAC of the applicant id with a key that
 * stays in the orchestrator's environment. Carried in the decision event and split off to the
 * restricted index cc-decision-inputs by Logstash.
 */
public record DecisionInputs(
    int age,
    Province province,
    ResidencyStatus residencyStatus,
    EmploymentStatus employmentStatus,
    BigDecimal annualIncome,
    int monthsAtEmployer,
    BigDecimal monthlyHousingCost,
    BigDecimal monthlyDebt,
    double tds,
    int creditScore,
    boolean bankruptcyFlag,
    int delinquencies,
    int inquiriesLast6Months,
    int fileAgeMonths,
    BigDecimal existingCreditLimit,
    BigDecimal requestedLimit,
    Product requestedProduct,
    String applicantToken) {}
