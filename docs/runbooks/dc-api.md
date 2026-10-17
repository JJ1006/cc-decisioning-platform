# Decision Center REST API (ODM 9.5.0.1), as used by `scripts/deploy-dc.sh`

Taken from the live OpenAPI document of the local container, not from memory:
`https://localhost:9443/decisioncenter-api/v3/api-docs` (Swagger UI: `/decisioncenter-api/swagger-ui/index.html`).
Base URL `https://localhost:9443/decisioncenter-api`, HTTP basic auth with the ODM for Developers demo user `odmAdmin`,
Mutual TLS since P6: verified against the project CA from `make pki`, and ODM requires a client certificate (`scripts/dc.sh` presents the `admin` one). `scripts/dc.sh <curl args> <path>` wraps all of that.

## Endpoints we use

| Step | Call |
|---|---|
| list decision services | `GET /v1/decisionservices` |
| import (new) | `POST /v1/decisionservices/import` multipart field `file` (zip); response `{decisionService:{id,...}, published:N}` |
| import (onto existing branch) | `POST /v1/decisionservices/{id}/import?override=` |
| export | `GET /v1/decisionservices/{id}/export` (zip) |
| delete | `DELETE /v1/decisionservices/{id}` |
| deployment configs | `GET /v1/decisionservices/{id}/deployments` |
| build RuleApp, returns the validation report | `POST /v1/deployments/{depId}/build` returns `{status: COMPLETED or FAILED, messages.elements[{severity,message,ruleArtifact.path}]}` |
| deploy to RES | `POST /v1/deployments/{depId}/deploy?serverId=<id>&rulesetsNewVersions=ccApproval:1.0` |
| download RuleApp jar | `GET /v1/deployments/{depId}/download` |
| servers | `GET /v1/servers` (`Decision Service Execution` is the RES at `http://localhost:9060/res`) |

`rulesetsNewVersions` is only accepted when the deployment configuration's version policy is MANUAL; the generator sets
that as default, so redeploying replaces `ccApprovalApp/1.0/ccApproval/1.0` instead of creating 1.1, 1.2, and so on.

Other useful ones: `GET /v1/projects/{id}/rules|ruleflows|variablesets|operations`, `GET /v1/testsuites/{id}` and
`POST /v1/testsuites/{id}/run`, `POST /v1/decisionservices/{id}/snapshot`, `GET /v1/about`.

## RES (execution)

| Call |
|---|
| `GET /res/apiauth/v1/ruleapps/{name}/{version}/rulesets` (list; used to verify a deploy) |
| `POST /res/apiauth/v1/ruleapps` multipart `file` (path B, `scripts/deploy-ruleapp.sh`) |
| `POST /DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0` body `{"application":{...}}`, response `{"decision":{...}}` |

## Notes learned the hard way

- The ODM container starts empty. `ODM_SAMPLE=true make up PROFILE=core` loads two samples (Loan Validation, Miniloan);
  their exports were the format templates for `scripts/gen_dc_project.py`. The container database is not persistent, so
  `make deploy-rules` re-imports everything every time.
- A build reports one stage of errors at a time. Loop with `scripts/dc-cycle.sh` (regenerate, import, build, print messages).
- The OUT parameter `decision` is created by the variable's `initialValue` (Java syntax: `new ca...Decision()`); BAL
  constructor phrases and ruleflow initial actions did not parse.
- `is not null` is rejected for `BigDecimal` ("predicate not applicable to value types"), so `requestedLimit` is a mandatory input.
- A zero-argument method returning a value must be verbalized with `#phrase.navigation`, not `#phrase.action`.
