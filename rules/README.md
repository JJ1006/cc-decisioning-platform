# Rules (ODM): Rule Designer to RuleApp

Everything the ccApproval ruleset needs, as reviewable files. Rule Designer (an Eclipse IDE) is a GUI, so a few steps
below are yours to do once; everything after the export is scripted.

```text
rules/xom/                  Java execution model (Maven module, built in CI)
rules/cc-approval-rules/    rule sources: bom/ (verbalization), rules/<package>/*.brl, decision-tables/, ruleflow/
rules/cc-approval-ds/       decision service descriptor + ruleset properties (agent.enabled, agent.description)
rules/tests/                scenarios.json (30 cases) + check_scenarios.py (spec check, no ODM needed)
rules/ruleapps/             exported RuleApp archive: ccApprovalApp.jar   <- you produce this
docs/rules-catalog.md       the human spec: rule id, intent, condition, action, reason code
```

Rule Designer project files (`.project`, ruleflow and decision-table editors) are IDE-generated, so the repo keeps the
sources in open formats (BAL text, `.voc`, CSV, Markdown) and you assemble the projects below. This takes about 30 minutes once.

## 1. Install Rule Designer (free ODM for Developers)

Rule Designer must match the server: the compose stack runs RES **9.5.0.1** (`icr.io/cpopen/odm-k8s/odm:9.5.0.1-IF019_25.1.0`).

1. Download **IBM Operational Decision Manager for Developers 9.5.0.1** from IBM's ODM for Developers page (free, IBM id
   required). Pick the package that includes Rule Designer for macOS (Apple Silicon is fine; a x86_64 build runs under Rosetta).
2. Install a compatible Eclipse (the ODM installer or release notes name the supported version, normally an Eclipse
   "IDE for Java Developers" release) with **JDK 21**, or use the Eclipse bundled with the download if there is one.
3. In Eclipse: Help > Install New Software > Add > Local, and pick the Rule Designer update site folder from the ODM
   download (`rds` / `Rule Designer` p2 repository). Tick **Rule Designer**, install, restart.
4. Open the **Rule** perspective (Window > Perspective > Open Perspective > Other > Rule). Help > About should show 9.5.0.1.
5. Build the XOM once: `mvn -B -ntp -pl rules/xom package`. It produces `rules/xom/target/cc-xom-*.jar`.

## 2. Create the rule project and the BOM

1. File > New > **Rule Project** > Standard Rule Project. Name `cc-approval-rules`, location `<repo>/rules/cc-approval-rules`
   (untick "Use default location" and point at this folder, so Rule Designer works in the git tree).
2. On the XOM page add the XOM jar from step 1.5 (or the `rules/xom` Java project if you import it as Maven).
3. New > BOM Entry > **Java Execution Object Model**; select all classes under `ca.maplenorthern.cc.xom`. Untick `dateOfBirth` and `sin`.
4. Open the BOM editor, Verbalization: import / overwrite with `bom/model_en_US.voc` (see `bom/README.md`).
   Do not add members for protected characteristics; the lint fails on them.
5. Add the ruleset parameters when you create the decision service (step 4): `application` IN, `decision` OUT.

## 3. Rules, tables and ruleflow

1. Create the five rule packages `eligibility`, `risk`, `limit`, `product`, `reasons`.
2. For every file in `rules/<package>/*.brl`: New > Action Rule, same name, paste the text. Fix any verbalization
   mismatch in the editor (the `.voc` and the BAL text were written together, so differences should be small).
3. Decision tables (`decision-tables/*.csv`, specified in `docs/rules-catalog.md`): New > Decision Table in the
   package named in the README of that folder, and enter the rows.
4. Ruleflow: build `ccApproval` exactly as in `ruleflow/ccApproval.md`, including the initial action
   `set 'the decision' to a new decision ;`.

Check: the Problems view is empty, then run `python3 rules/tests/check_scenarios.py` (spec check) and, in Rule Designer, a
couple of Scenario Suites from `rules/tests/scenarios.json` if you like. The authoritative run is `make test-rules`.

## 4. Decision service and Decision Center

1. New > **Decision Service** project `cc-approval-ds` in `rules/cc-approval-ds`, referencing `cc-approval-rules`.
2. Add the operation `ccApproval` as described in `cc-approval-ds/decision-service.yaml`: ruleset parameters
   `application` (IN, verbalization `the application`) and `decision` (OUT, `the decision`), main ruleflow `ccApproval`.
3. Set the ruleset properties from `cc-approval-ds/ruleset.properties` (`agent.enabled=true`, `agent.description=...`)
   so the operation is exposed to MCP/agents.

**Link and sync with Decision Center** (business users then edit rules there):

1. `make up PROFILE=core` and wait until `odm` is healthy. Decision Center is at `https://localhost:9443/decisioncenter`.
2. In Rule Designer: Window > Preferences > Rule Designer > Decision Center > connection URL
   `https://localhost:9443/decisioncenter`, user `odmAdmin` (ODM for Developers demo user). The server certificate is signed by
   our CA: import `security/pki/out/ca/ca.crt` into the JDK trust store Rule Designer uses (`keytool -importcert -cacerts`
   on its JRE), or Rule Designer rejects the connection.
3. Right-click `cc-approval-ds` > Decision Center > **Connect**, then **Synchronize** (Rule Designer to Decision Center).

## 5. Build and export the RuleApp

1. Right-click the decision service project > Export > **Rule Designer > RuleApp Archive** (or Decision Service > Deploy > export).
2. RuleApp name `ccApprovalApp`, version `1.0`, ruleset `ccApproval` version `1.0` (deployed path `/ccApprovalApp/1.0/ccApproval/1.0`).
3. Save as `rules/ruleapps/ccApprovalApp.jar`. Do not commit extra IDE output.

## 6. Deploy and test (scripted)

```bash
make up PROFILE=core          # ODM on https://localhost:9443 (container 9453), certificates from make pki
make deploy-rules             # POST the jar to RES over HTTPS, verify the ruleset is listed
make test-rules               # 30 scenarios against /DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0
make lint-rules               # prohibited-grounds lint + spec check, no ODM needed
```

`deploy-rules` and `test-rules` use the demo user `resAdmin` by default; override with `RES_USER`, `RES_PASS`,
`RES_URL`, `RES_CA`. Request shape: `{"application": {...}}`, response `{"decision": {...}}`.

If an endpoint differs on your build, check Swagger at `https://localhost:9443/res/api/swagger` and set `RES_API` / `EXEC_URL`.
