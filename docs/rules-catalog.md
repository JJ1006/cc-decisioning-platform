# Rules catalog: ccApproval ruleset

Maple Northern Bank is fictitious; all data is synthetic. ODM is the system of record for the credit decision; an LLM
never decides or overrides it. This catalog is the human spec. The `.brl` files in `rules/cc-approval-rules/rules/` and the
two decision tables in `rules/cc-approval-rules/decision-tables/` implement it, and `rules/tests/check_scenarios.py` restates
it in Python to prove `rules/tests/scenarios.json` agrees with it.

**Severity.** Rules call `escalate`; the most severe outcome wins: DECLINE > REFER > APPROVE. Rule order inside a task
therefore does not change the outcome. Every rule that applies adds its reason code, so adverse-action notices are complete.

**Flow** (`rules/cc-approval-rules/ruleflow/ccApproval.md`): eligibility, then (only if no outcome yet) risk, then (only if
still no outcome) limit, product, reasons. An applicant who passes all checks has no outcome until RSN-01 approves.

**Age of majority** (in the `Province` XOM enum and rule ELIG-01): 19 in BC, NB, NL, NS, NT, NU, YT; 18 in AB, MB, ON, PE, QC, SK.

| Rule id | Package | Intent | Condition | Action | Reason code |
|---|---|---|---|---|---|
| ELIG-01 | eligibility | Contract capacity | age in years < age of majority of the applicant's province | DECLINE | R_AGE_BELOW_MAJORITY |
| ELIG-02 | eligibility | Residency | residency status is NON_RESIDENT or WORK_PERMIT_EXPIRED | DECLINE | R_NON_RESIDENT |
| ELIG-03 | eligibility | No active insolvency | bureau reports an active bankruptcy | DECLINE | R_ACTIVE_BANKRUPTCY |
| RISK-01 | risk | Minimum income | annual income < 15,000 | DECLINE | R_INSUFFICIENT_INCOME |
| RISK-02 | risk | Payment history | delinquencies (24 months) >= 3 | DECLINE | R_DELINQUENCIES |
| RISK-03 | risk | Credit-seeking behaviour | hard inquiries (6 months) > 6 | DECLINE | R_EXCESSIVE_INQUIRIES |
| RISK-04 | risk | Enough history to assess | file age < 6 months | REFER | R_THIN_FILE |
| RISK-DT-01 | risk | Score x debt service (decision table `ScoreTdsOutcome`) | see table below | DECLINE / REFER / none | R_LOW_SCORE, R_HIGH_TDS, R_BORDERLINE_SCORE, R_BORDERLINE_TDS |
| LIM-DT-01 | limit | Base limit by income and score (decision table `IncomeScoreLimit`) | see table below | set credit limit | none |
| LIM-02 | limit | Never exceed what was asked for | requested limit present and credit limit > requested limit | credit limit = requested limit | none |
| PROD-01 | product | Entry product | requested product is NO_FEE or absent | product NO_FEE | none |
| PROD-02 | product | Cashback eligibility | requested CASHBACK, income >= 40,000, score >= 660 | product CASHBACK | none |
| PROD-03 | product | Cashback not met | requested CASHBACK, income < 40,000 or score < 660 | product NO_FEE | none |
| PROD-04 | product | Travel eligibility | requested TRAVEL_INFINITE, income >= 80,000, score >= 720 | product TRAVEL_INFINITE | none |
| PROD-05 | product | Travel not met, cashback met | requested TRAVEL_INFINITE, travel not met, cashback met | product CASHBACK | none |
| PROD-06 | product | Neither met | requested TRAVEL_INFINITE, travel not met, cashback not met | product NO_FEE | none |
| APR-01 | product | Best rate | score >= 740 | APR tier TIER_1 | none |
| APR-02 | product | | 680 <= score <= 739 | APR tier TIER_2 | none |
| APR-03 | product | | 650 <= score <= 679 | APR tier TIER_3 | none |
| APR-04 | product | | score <= 649 | APR tier TIER_4 | none |
| RSN-01 | reasons | Approve when nothing objected | no outcome set yet | APPROVE | R_APPROVED_STANDARD |

## ScoreTdsOutcome (RISK-DT-01)

Total debt service ratio (TDS) = (monthly debt payments + monthly housing cost) / (annual income / 12).

| Score \ TDS | <= 0.35 | (0.35, 0.42] | (0.42, 0.50] | (0.50, 0.60] | > 0.60 |
|---|---|---|---|---|---|
| < 560 | DECLINE R_LOW_SCORE | DECLINE R_LOW_SCORE | DECLINE R_LOW_SCORE | DECLINE R_LOW_SCORE | DECLINE R_LOW_SCORE |
| 560-619 | REFER R_BORDERLINE_SCORE | DECLINE R_HIGH_TDS | DECLINE R_HIGH_TDS | DECLINE R_HIGH_TDS | DECLINE R_HIGH_TDS |
| 620-679 | approve | approve | REFER R_BORDERLINE_TDS | DECLINE R_HIGH_TDS | DECLINE R_HIGH_TDS |
| >= 680 | approve | approve | approve | REFER R_BORDERLINE_TDS | DECLINE R_HIGH_TDS |

## IncomeScoreLimit (LIM-DT-01), CAD

| Annual income \ Score | 620-679 | 680-739 | >= 740 |
|---|---|---|---|
| < 30,000 | 1,500 | 3,000 | 5,000 |
| 30,000-59,999 | 3,000 | 6,000 | 10,000 |
| 60,000-99,999 | 6,000 | 12,000 | 20,000 |
| >= 100,000 | 10,000 | 20,000 | 35,000 |

## Fairness and compliance notes

- **Prohibited grounds.** No rule, BOM member or vocabulary entry uses a protected characteristic. `scripts/lint-prohibited-grounds.sh` enforces this in CI and `XomTest` checks the XOM properties.
- **PIPEDA.** Synthetic data only; the SIN field is never read by a rule and is masked before indexing.
- **OSFI E-23 (model risk).** Rules are versioned in git and Decision Center, every decision lists the rules that fired, and `scenarios.json` is the regression suite for any change.
- **FCAC disclosure.** Reason codes carry applicant-facing text (`ReasonCode.getDescription()`) for adverse-action notices.
