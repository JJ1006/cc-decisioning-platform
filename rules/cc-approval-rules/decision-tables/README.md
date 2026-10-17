# Decision tables

Two tables, kept as CSV (one row = one table row). `scripts/gen_dc_project.py` turns them into Decision Center decision
tables (`risk/ScoreTdsOutcome`, `limit/IncomeScoreLimit`); the same data is spelled out in `docs/rules-catalog.md`.

| Table | Package | Conditions | Actions |
|---|---|---|---|
| `ScoreTdsOutcome` | `risk` | bureau score band, total debt service ratio band (above X, up to Y) | escalate the decision, add reason code, record rule |
| `IncomeScoreLimit` | `limit` | annual income band, bureau score band | set credit limit, record rule |
