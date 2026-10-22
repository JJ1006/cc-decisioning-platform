---
description: Bring the local stack up, run the demo, print the key URLs
argument-hint: [applications, default 400]
---

1. `make pki` (idempotent), then `make up PROFILE=core,analytics,edge` (needs about 6 GB Docker; stop other stacks first). If Docker is not running or memory is short, print `NEED USER: <what>` and stop.
2. `make demo` (or `PROFILE=core scripts/demo.sh ${ARGUMENTS:-400} 40 0.01`): show the topic and index counts and every PASS/FAIL line.
3. `scripts/verify-mtls.sh kafka` for the ACL checks.
4. Print the URLs:
   - Kibana: <https://localhost:5601/s/maple-northern/app/dashboards> (user `analyst`, `ops`; passwords: `ANALYST_PASSWORD`, `OPS_PASSWORD` in the root env file - do not print them)
   - Gateway: <https://localhost:8443> (client certificate: `scripts/api-call.sh <consumer> /v1/...`)
   - Decision Center / RES: <https://localhost:9443> (browser needs the admin certificate, see `docs/security-mtls.md`)
   - kafka-ui: <http://127.0.0.1:8080>
   - Grafana (only with `PROFILE=core,edge,obs`): <https://localhost:3000>
5. Suggest the next step: `/explain <applicationId>` on a REFER case (`scripts/api-call.sh analyst "/v1/decisions?outcome=REFER&size=3"`).
