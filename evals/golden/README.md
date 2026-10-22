# Golden dataset

`golden.json`: 300 labelled applications (the same request shape as `rules/tests/scenarios.json`). It is **generated** by `generate.py` and labelled by `oracle.py`; ODM is never
asked for a label.

* `oracle.py` is an independent implementation of the ruleset. It reads the two decision tables (`ScoreTdsOutcome`, `IncomeScoreLimit`) and the age of majority from the markdown of
  `docs/rules-catalog.md` at run time, and holds the prose thresholds (minimum income, delinquencies, product and APR floors) as constants that `check_catalog_constants()`
  compares with the catalog text, so a change of the catalog that the oracle does not know fails loudly. Exact arithmetic (fractions) is used for the debt service ratio.
* `generate.py` writes about 177 hand-designed edge cases (age of majority one year below and at it in each of the 13 provinces and territories, every TDS boundary at -0.01 / exact / +0.01
  in three score rows, every score band edge, income edges of the limit table in every score column, product eligibility edges, the requested limit cap, product fallbacks, all
  residency and employment values, bankruptcy, delinquency, inquiry and file-age boundaries, stacked reasons) and fills up to 300 with a seeded random mix (`SEED = 14`). It asserts that every
  reason code occurs in at least 3 cases.
* `test_oracle.py` tests the oracle (including: it agrees with the 30 hand-written scenarios of `rules/tests`, which were written before it, and the catalog guard fails when the
  catalog text changes).

## Add a case

1. A new edge case: add an `add(...)` line in the hand-written block of `generate.py` (name, tag, overrides of `base()`), then `python3 evals/golden/generate.py`. The label comes from the oracle.
2. A new rule or threshold: change `docs/rules-catalog.md` first (the oracle follows the two tables; for a prose threshold also change the constant and its entry in `check_catalog_constants`),
   then the rule sources, then add boundary cases (both sides of every new threshold). `make deploy-rules && make eval-rules`: the gate must be green.
3. `make test-evals` (also in `make verify`) fails when `golden.json` is not what `generate.py` writes.

A difference between the oracle and ODM is either a bug in the rules (fix the rule: the first run of this gate found PROD-02..06 failing with a NullPointerException when the requested product
is absent) or a spec change that the catalog and the oracle have not yet followed (fix the catalog). Never edit `golden.json` by hand.
