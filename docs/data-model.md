# Data model

Source of truth: JSON Schemas in [`contracts/`](../contracts) (events) and the Java XOM in [`rules/xom`](../rules/xom) (rule input/output).
The XOM contains no prohibited grounds (gender, ethnicity, marital status, religion). Age is used only for the provincial age-of-majority check.

```mermaid
classDiagram
  class Application {
    applicationId
    requestedProduct : Product
    requestedLimit
    monthlyHousingCost
    channel : Channel
    tdsRatio() double
  }
  class Applicant {
    applicantId
    firstName
    lastName
    dateOfBirth
    ageYears
    sin
    residencyStatus : ResidencyStatus
  }
  class Address {
    line1
    city
    province : Province
    postalCode
  }
  class Employment {
    status : EmploymentStatus
    employer
    annualIncome
    monthsAtEmployer
  }
  class CreditBureauReport {
    score 300-900
    monthlyDebtPayments
    existingCreditLimit
    delinquencies
    inquiriesLast6Months
    activeBankruptcy
    fileAgeMonths
  }
  class Decision {
    outcome : Outcome
    creditLimit
    product : Product
    aprTier : AprTier
    reasonCodes : ReasonCode[]
    firedRules : String[]
  }
  Application *-- Applicant
  Application *-- Address
  Application *-- Employment
  Application *-- CreditBureauReport
  Application ..> Decision : ccApproval ruleset
```

## Event envelope

Every Kafka value is JSON with this envelope (`contracts/envelope.schema.json`):

| Field | Type | Notes |
|---|---|---|
| `eventId` | uuid | unique per event |
| `correlationId` | string | follows one application end to end; also the `X-Correlation-Id` header and the log MDC key |
| `eventType` | string | equals the topic name, e.g. `cc.decisions.approved.v1` |
| `schemaVersion` | int | bump on breaking payload change; add a `.v2` topic |
| `occurredAt` | date-time | UTC |
| `producer` | string | service name |
| `payload` | object | per-event schema |

## Schemas

| Schema | Payload (Java) |
|---|---|
| `application-submitted` | `ApplicationSubmittedPayload` |
| `decision-approved` / `declined` / `referred` | `DecisionPayload` (outcome fixed per schema) |
| `decision-manual` | `ManualDecisionPayload` |
| `notification` | `NotificationPayload` |
| `audit` | `AuditPayload` |
| `dlq` | `DlqPayload` |

Shared definitions (enums, `application`, `decisionPayload`) live in `defs.schema.json` and mirror the XOM enums.
`DecisionPayload.applicantSummary` carries only non-identifying attributes for analytics and fairness reporting: province, age band and, in `cc-decisions`, only BANDS of the rest (`scoreBand`, `tdsBand`, `incomeBand`, `limitBand`; the event itself still carries the exact `bureauScore`, `tdsRatio` and `annualIncome`, which Logstash removes before indexing).
`DecisionPayload.inputs` (P12, optional: older events have none) is what the rules saw: age, province, residency and employment status, income, months at employer, housing cost, monthly debt, TDS, score,
bankruptcy flag, delinquencies, inquiries, file age, existing and requested limit, requested product, and `applicantToken` (HMAC-SHA-256 of the applicant id). No direct identifier. `rulesetPath` and
`rulesetDeployedAt` say which ruleset decided. Logstash splits the inputs into the restricted index `cc-decision-inputs` and adds `scoreBand`, `tdsBand`, `incomeBand` and `limitBand` to the summary in `cc-decisions` and drops the exact score, TDS and income from it (exact values only in `cc-decision-inputs`; the query API reads the score from there for `bureauScore`, `minScore` and `maxScore`). Band labels are zero-padded so they sort as text.
Audit records of an MCP tool call have `eventType: cc.audit.mcp.v1` (same topic).

## Validation

- `scripts/validate-contracts.py` validates every `contracts/examples/*.example.json` against its schema (CI).
- `ContractRoundTripTest` in `common-lib` validates each example, deserializes it into the DTO, serializes it back and checks it is unchanged and still valid.
