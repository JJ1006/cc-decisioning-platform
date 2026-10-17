# Rules (ODM): git to Decision Center to RES

The ccApproval ruleset is built headless: sources in git are turned into a Decision Center (DC) project by a script, built
and deployed to Rule Execution Server (RES) through the DC REST API. No IDE is needed. Rule Designer is optional for
developers who prefer it (last section).

```text
rules/xom/                  Java execution model (Maven module, built in CI)
rules/cc-approval-rules/    sources: rules/<package>/*.brl (BAL text), bom/ (spec), decision-tables/*.csv, ruleflow/ccApproval.md
rules/cc-approval-ds/       decision service descriptor + ruleset.properties (agent.enabled, agent.description)
rules/dc-export/            what Decision Center holds, exported and unpacked (committed; refresh with make dc-export)
rules/tests/                scenarios.json (30 cases) + check_scenarios.py (spec check, no ODM needed)
rules/ruleapps/             ccApprovalApp.jar written by make deploy-rules (gitignored, regenerate any time)
docs/rules-catalog.md       the human spec: rule id, intent, condition, action, reason code
docs/runbooks/dc-api.md     the DC and RES endpoints we use, from the live OpenAPI document
```

## Quick start

```bash
make pki                      # once
make up PROFILE=core          # ODM at https://localhost:9443 (container port 9453); wait until healthy
make deploy-rules             # generate, import to DC, build (fails on errors), deploy to RES, save the jar
make test-rules               # spec check + 30 scenarios against /DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0
make lint-rules               # prohibited-grounds lint + spec check, no ODM needed
make dc-export                # refresh rules/dc-export after editing in the Business Console
```

The ODM container database is not persistent, so `make deploy-rules` rebuilds Decision Center from git every time.
Demo users (`odmAdmin`, `resAdmin`) come with ODM for Developers; override with `DC_USER`, `DC_PASS`, `RES_USER`, `RES_PASS`,
`RES_URL`, `RES_CA`.

## How it works

1. `scripts/gen_dc_project.py` writes `tmp/cc-approval.zip` in the exact format of a DC export (copied from the Miniloan
   sample, see `docs/runbooks/dc-api.md`):
   - BOM and vocabulary generated from the XOM sources (`rules/xom`), XOM classes shipped as a managed XOM library;
   - one `.brl` per rule from `rules/cc-approval-rules/rules/<package>/` (packages `eligibility`, `risk`, `limit`,
     `limitcap`, `product`, `reasons`; `limitcap` exists because the cap rule must run after the limit table);
   - the two decision tables from `decision-tables/*.csv`, the ruleflow from `ruleflow/ccApproval.md`;
   - operation `ccApproval` (`application` IN, `decision` OUT) and a deployment configuration to RuleApp `ccApprovalApp`
     with ruleset properties `agent.enabled=true` and `agent.description` from `cc-approval-ds/ruleset.properties`.
2. `scripts/deploy-dc.sh` imports it (`POST /v1/decisionservices/import`), builds it (`POST /v1/deployments/{id}/build`,
   non-zero exit with the DC messages on any error), deploys it (`.../deploy`) and downloads the RuleApp archive.
3. `scripts/test-ruleset.sh` calls the RES execution API and diffs outcome, credit limit, product, APR tier and sorted
   reason codes. If a case fails, fix the rule in the generator or sources; change a scenario only when
   `docs/rules-catalog.md` says the scenario is wrong.

Changing a rule: edit the `.brl` (or CSV, or the generator for BOM/ruleflow), update `docs/rules-catalog.md` and
`rules/tests/check_scenarios.py` in the same commit, run `make deploy-rules test-rules`, then `make dc-export`.

## Path B: push a RuleApp archive straight to RES

`make deploy-rules VIA=res` uploads `rules/ruleapps/ccApprovalApp.jar` to RES (`scripts/deploy-ruleapp.sh`,
`POST /res/apiauth/v1/ruleapps`), for an archive exported from Decision Center or Rule Designer.

## Business users

Open `https://localhost:9443/decisioncenter` (trust the project CA from `make pki`), log in as `odmAdmin`, open the
decision service `cc-approval`. Edits made there are not in git until you run `make dc-export` and commit `rules/dc-export/`.

## Optional: Rule Designer

Not required. If you want the IDE (Eclipse plus ODM for Developers 9.5.0.1 Rule Designer, matching the server): install
it from the ODM for Developers download, connect it to `https://localhost:9443/decisioncenter` (trust the project CA in
its JRE), and use Import > Decision Center to pull `cc-approval`, or import `rules/dc-export/` as existing projects.
Rule Designer and the generator use the same file formats, so changes round trip through `make dc-export`.
