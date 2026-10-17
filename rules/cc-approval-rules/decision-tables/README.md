# Decision tables

Rule Designer decision tables are IDE-generated artifacts, so the two tables are kept here as plain CSV
(one row = one table row) and built by hand once, in about five minutes each. See `rules/README.md` step 6.

| Table | Package | Conditions | Actions |
|---|---|---|---|
| `ScoreTdsOutcome` | `risk` | bureau score band, total debt service ratio band | `escalate the decision to <outcome>`, `add reason code <code> to the decision`, `record that rule "RISK-DT-01" fired` |
| `IncomeScoreLimit` | `limit` | annual income band, bureau score band | `set the credit limit of the decision to <amount>`, `record that rule "LIM-DT-01" fired` |

Expressions: score = `the bureau score of the bureau report of 'the application'`, TDS =
`the total debt service ratio of 'the application'`, income = `the annual income of the employment of 'the application'`.

`scripts/` has no importer on purpose: after building the tables, `make test-rules` proves they behave like the CSV.
