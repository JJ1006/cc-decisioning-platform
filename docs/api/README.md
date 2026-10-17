# APIs (P5, behind the gateway since P6)

Two HTTP APIs sit behind the Apache APISIX gateway. The gateway is the only way in: `https://localhost:8443`, **mutual TLS, a client
certificate signed by our private CA is required**. The services publish no host ports (`COMPOSE_DEBUG=1` for debugging, see
[docs/security-mtls.md](../security-mtls.md)). Fictitious bank, synthetic data.

| API | Behind the gateway | Compose profile | Does | OpenAPI |
|---|---|---|---|---|
| `application-api` | `/v1/applications*` | `edge` | submit applications, application status, underwriter decisions on referrals | [application-api.yaml](application-api.yaml) |
| `decision-query-api` | `/v1/decisions*`, `/v1/stats/*`, `/v1/ops/*` | `analytics` | decisions, explanations, statistics, fairness, dead letters, logs (the only reader of Elasticsearch) | [decision-query-api.yaml](decision-query-api.yaml) |
| `txn-generator` admin | `/admin/generator/*` | `core` | start and stop the synthetic application generator | |
| MCP servers | `/mcp/*` | | placeholder route, answers 501 until phase 9 | |

```text
make pki && make up PROFILE=core,analytics,edge   # about 6 GB of Docker memory; stop kafka-ui and kibana if it is tight
make demo                                         # decisions to query
make test-api-docs                                # runs every example below, in order, and fails if one does not behave
```

## Who may call what

The client certificate's CN is the consumer. The gateway maps it to a role and checks the role against the route. The services trust the
role only because the connection presents the gateway's own certificate ([docs/security-mtls.md](../security-mtls.md)).

| Certificate (`security/pki/out/<name>/`) | Consumer role | May call |
|---|---|---|
| `demo-partner` | PARTNER | `POST /v1/applications`, `GET /v1/applications/{id}/status` |
| `underwriter-portal` | UNDERWRITER | `POST /v1/applications/{id}/manual-decision`, `GET /v1/applications/{id}/status`, `GET /v1/decisions/{id}` and `/explanation` |
| `analyst` | ANALYST | `GET /v1/decisions`, `GET /v1/decisions/{id}...`, `GET /v1/stats/*` |
| `ops-admin` | OPS | `GET /v1/ops/*`, `/admin/generator/*` |
| `load-test` | ANALYST | same as `analyst`; used by `make verify-mtls` for the rate-limit checks |

Anything else is `403` (problem+json, from the gateway). No certificate, or one from another CA, fails in the TLS handshake. Each consumer has
a quota of 100 requests per minute (`429` beyond it); `POST /v1/applications` also has a spike arrest of 5 requests per second per consumer.

Rules shared by both APIs:

* **Errors** are RFC 7807 `application/problem+json` with `type`, `title`, `status`, `detail`, `instance` and a `correlationId` member
  (plus `errors` when there are several validation messages). Nothing else is ever returned for an error: no stack traces.
* **`X-Correlation-Id`** is read from the request (the gateway creates one when missing) and echoed on the response. It is also the key
  that ties an application's events and log lines together (`/v1/ops/logs`).
* **Role** (manual decisions): `UNDERWRITER`, taken from the client certificate through the gateway. Another role is 403 and the refusal is
  written to the audit topic with result `DENIED`. The underwriter id is in the body for now (the portal passes the human).
* **Idempotency-Key** (`POST`): the first response is remembered for 24 hours per key (in memory, per instance, see ADR-0010). The same key
  with the same body replays it (`Idempotent-Replayed: true`, no second event); the same key with another body is 422.
* **DTOs only**: indexed documents are PII-masked by Logstash, and the API maps them to response types, so no document is returned as it is
  stored. Personal data never leaves: no SIN, names, address or date of birth, only province and age band.

The examples share shell variables, so run them in order in one shell (`make test-api-docs` does exactly that). `api CLIENT [curl args] /path`
is `scripts/api-call.sh` (also `make api-call CLIENT=analyst P=/v1/stats/latency`): curl to the gateway with that consumer's certificate and
the project CA.

```bash
api() { scripts/api-call.sh "$@"; }
PKI=security/pki/out
CID="readme-$RANDOM"          # one correlation id for the whole walk-through
```

## Transport: no certificate, wrong CA, wrong role

```bash
# no client certificate: the TLS handshake fails (curl exit status is not 0)
if curl -s -o /dev/null --cacert "$PKI/ca/ca.crt" https://localhost:8443/v1/stats/latency; then echo "unexpected: no certificate accepted"; false; fi
echo "no certificate: handshake refused"
# a role may only call its routes
test "$(api analyst -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{}' /v1/applications)" = 403
api analyst -X POST -H 'Content-Type: application/json' --data '{}' /v1/applications | jq -c '{status, title, detail}'
```

## Submit an application

`applicationId` is optional: the server assigns one. The body is validated against the `application` definition of the contracts
(`contracts/defs.schema.json`) and the event is checked against `application-submitted.schema.json` before it is published to
`cc.applications.submitted.v1` (key = application id). Answer: `202`, the id, and a `Location` header.

```bash
jq '.payload.application | del(.applicationId)' contracts/examples/application-submitted.example.json > "${TMPDIR:-/tmp}/cc-application.json"
KEY=$(uuidgen)
api demo-partner -i -X POST -H 'Content-Type: application/json' -H "X-Correlation-Id: $CID" \
  -H "Idempotency-Key: $KEY" --data @"${TMPDIR:-/tmp}/cc-application.json" /v1/applications | tee "${TMPDIR:-/tmp}/cc-submit.txt"
APP_ID=$(tail -1 "${TMPDIR:-/tmp}/cc-submit.txt" | jq -r .applicationId)
echo "application: $APP_ID"
grep -qE '^HTTP/[0-9.]+ 202' "${TMPDIR:-/tmp}/cc-submit.txt"
grep -qi "^location: /v1/applications/$APP_ID/status" "${TMPDIR:-/tmp}/cc-submit.txt"
```

Typical answer (the gateway adds the security headers):

```text
HTTP/2 202
location: /v1/applications/APP-5E1C0B7A-6F2B-4B/status
x-correlation-id: readme-3217
strict-transport-security: max-age=31536000; includeSubDomains
content-type: application/json

{"applicationId":"APP-5E1C0B7A-6F2B-4B","status":"SUBMITTED","correlationId":"readme-3217"}
```

Retry with the same `Idempotency-Key` and body: same answer, `Idempotent-Replayed: true`, no second event. Another body under the same key is 422:

```bash
api demo-partner -i -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  --data @"${TMPDIR:-/tmp}/cc-application.json" /v1/applications | grep -iE '^(HTTP|idempotent-replayed)'
test "$(jq '.requestedLimit = 99999' "${TMPDIR:-/tmp}/cc-application.json" \
  | api demo-partner -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" --data @- /v1/applications)" = 422
```

A body that breaks the contract is a 400 with every violation listed:

```bash
jq 'del(.bureau) | .channel = "FAX"' "${TMPDIR:-/tmp}/cc-application.json" \
  | api demo-partner -X POST -H 'Content-Type: application/json' -H "X-Correlation-Id: $CID" --data @- /v1/applications | jq -c .
```

```json
{"type":"about:blank","title":"Invalid application","status":400,"detail":"the application does not match the contract","instance":"/v1/applications","errors":["$.payload.application.bureau: is missing but it is required","$.payload.application.channel: does not have a value in the enumeration [WEB, MOBILE, BRANCH, PARTNER]"],"correlationId":"readme-3217"}
```

## Status

`SUBMITTED` until the decision has been indexed (a few seconds), then `APPROVED`, `DECLINED` or `REFERRED`. After an underwriter decides,
the status is that decision and `decisionSource` is `MANUAL`. The status comes from `decision-query-api` (single reader of Elasticsearch)
over mutual TLS; `503` if it is down. An id this instance did not accept and no decision exists for is `404`.

```bash
for _ in $(seq 1 60); do
  STATUS=$(api demo-partner /v1/applications/$APP_ID/status | tee "${TMPDIR:-/tmp}/cc-status.json" | jq -r .status)
  [ "$STATUS" != SUBMITTED ] && break
  sleep 2
done
cat "${TMPDIR:-/tmp}/cc-status.json"; echo
test "$STATUS" != SUBMITTED
api demo-partner -o /dev/null -w 'unknown application: HTTP %{http_code}\n' /v1/applications/NOPE/status
```

## Manual decision on a referral

Only `REFERRED` applications, only role `UNDERWRITER` (certificate `underwriter-portal`). One Kafka transaction publishes
`cc.decisions.manual.v1` and an audit event (`MANUAL_DECISION`, with the consumer); Logstash indexes the decision with
`decisionSource: MANUAL`. `APPROVE` needs a positive `creditLimit` and a `product`; `DECLINE` needs `creditLimit: 0` and no product;
`reason` and `underwriterId` are required.

```bash
REF=""
for id in $(api analyst "/v1/decisions?outcome=REFER&size=100" | jq -r '.items[].applicationId'); do
  if [ "$(api underwriter-portal /v1/applications/$id/status | jq -r .status)" = REFERRED ]; then REF=$id; break; fi
done
echo "referred application without a manual decision: $REF"
test -n "$REF"
BODY='{"outcome":"APPROVE","creditLimit":3000,"product":"NO_FEE","reason":"Verified income by phone","underwriterId":"UW-77"}'
echo "partner certificate:"
api demo-partner -X POST -H 'Content-Type: application/json' --data "$BODY" /v1/applications/$REF/manual-decision | jq -c '{status,title,detail}'
echo "partner certificate that claims to be an underwriter (the header is replaced by the gateway):"
api demo-partner -X POST -H 'Content-Type: application/json' -H 'X-Role: UNDERWRITER' -H 'X-Consumer: underwriter-portal' --data "$BODY" /v1/applications/$REF/manual-decision | jq -c '{status,title}'
echo "underwriter certificate:"
api underwriter-portal -X POST -H 'Content-Type: application/json' -H "X-Correlation-Id: $CID" -H "Idempotency-Key: $(uuidgen)" \
  --data "$BODY" /v1/applications/$REF/manual-decision | tee "${TMPDIR:-/tmp}/cc-manual.json" | jq -c .
test "$(jq -r .decisionSource "${TMPDIR:-/tmp}/cc-manual.json")" = MANUAL
```

```json
{"applicationId":"APP-00000978","decisionId":"0b5d6a8e-1d3e-4a53-9f7b-0e4f4f0f6c11","status":"APPROVED","decisionSource":"MANUAL"}
```

Once it is indexed the application reads as decided by the underwriter, the explanation still shows the rules' REFER, and a second decision is
refused with 409:

```bash
for _ in $(seq 1 60); do
  [ "$(api underwriter-portal /v1/applications/$REF/status | jq -r .decisionSource)" = MANUAL ] && break
  sleep 2
done
api underwriter-portal /v1/applications/$REF/status | jq -c .
api underwriter-portal /v1/decisions/$REF | jq -c '{applicationId, outcome, decisionSource, creditLimit, product, underwriterId, note}'
api underwriter-portal -X POST -H 'Content-Type: application/json' --data "$BODY" /v1/applications/$REF/manual-decision | jq -c '{status,title,detail}'
api underwriter-portal -o /dev/null -w 'bad body: HTTP %{http_code}\n' -X POST -H 'Content-Type: application/json' --data '{"outcome":"APPROVE"}' /v1/applications/$REF/manual-decision
```

Both events are in Elasticsearch, and the refused attempts are in the audit trail (operator view, admin user, CA verified by `scripts/es-curl.sh`):

```bash
scripts/es-curl.sh "/cc-decisions/_search?q=payload.applicationId:$REF&size=5&_source=decisionSource,payload.outcome,payload.underwriterId" | jq -c '.hits.hits[]._source'
scripts/es-curl.sh "/cc-audit/_search?q=payload.resourceId:$REF%20AND%20payload.action:MANUAL_DECISION&size=5&_source=payload.actor,payload.result,payload.details.consumer" | jq -c '.hits.hits[]._source'
```

## Decisions

`GET /v1/decisions/{applicationId}` is the newest decision (manual wins over the rules' REFER). `/explanation` is the rules' view: fired rules,
reason codes with the applicant-facing text, the ruleset version, and the underwriter's decision when there is one.

```bash
api analyst /v1/decisions/$REF | jq -c .
api analyst /v1/decisions/$REF/explanation | jq .
api analyst -i /v1/decisions/NOPE | grep -iE '^(HTTP|content-type)'
```

`GET /v1/decisions` filters by `province`, `outcome`, `minScore`, `maxScore`; newest first; `size` 1 to 100 (default 20); page with
`searchAfter` set to the `nextSearchAfter` of the previous page (Elasticsearch `search_after` on time and event id, so paging is stable and
cheap at any depth). No `nextSearchAfter` means the last page.

```bash
api analyst "/v1/decisions?province=ON&outcome=DECLINE&minScore=500&maxScore=640&size=3" | jq -c '.items[] | {applicationId, outcome, province, bureauScore}'
NEXT=$(api analyst "/v1/decisions?size=2" | jq -r .nextSearchAfter)
api analyst "/v1/decisions?size=2&searchAfter=$NEXT" | jq -c '[.items[].applicationId], .nextSearchAfter'
api analyst "/v1/decisions?size=101" | jq -c '{status, title, detail}'
```

## Statistics

Statistics count the rules' (ODM) decisions: they carry the applicant summary, and a manual decision answers a REFER instead of measuring the
rules. Approval rate = approved divided by all decisions of the group.

```bash
api analyst "/v1/stats/approval-rate?groupBy=province" | jq -c '.overall, (.groups[:3][])'
api analyst "/v1/stats/approval-rate?groupBy=ageBand" | jq -c '.groups[]'
api analyst "/v1/stats/approval-rate?groupBy=day" | jq -c '.groups[]'
api analyst "/v1/stats/decline-reasons?top=5" | jq -c '.reasons[]'
api analyst /v1/stats/latency | jq -c .
```

`/v1/stats/fairness?by=province|ageBand` compares each group's approval rate with the overall rate. `disparateImpactRatio` is group rate divided by
overall rate and `flagged` is true below 0.8 (the four-fifths rule). Small groups give noisy ratios, so read `total` next to the ratio; the
numbers are a screening signal, not a finding.

```bash
api analyst "/v1/stats/fairness?by=ageBand" | jq -c '{overallApprovalRate, threshold}, (.groups[] | {key, total, approvalRate, disparateImpactRatio, flagged})'
api analyst "/v1/stats/fairness?by=province" | jq -c '[.groups[] | select(.flagged) | .key]'
api analyst "/v1/stats/fairness?by=income" | jq -c '{status, title, detail}'
```

## Operations

`/v1/ops/dlq` browses dead letters (newest first), filtered by `topic` (the original topic) and `from` (ISO-8601 failure time). Values are the
masked or redacted copies, cut to 256 characters. `/v1/ops/logs?correlationId=` traces one correlation id across services from the container
logs Filebeat ships (oldest first, at most 200 lines; Filebeat scans every 10 seconds, so give it a minute). Role OPS (`ops-admin`) only.

```bash
test "$(api analyst -o /dev/null -w '%{http_code}' /v1/ops/dlq)" = 403
api ops-admin "/v1/ops/dlq?topic=cc.applications.submitted.v1&size=2" | jq -c '{total, items: [.items[] | {eventId, errorClass, failedAt}]}'
api ops-admin "/v1/ops/dlq?from=2020-01-01T00:00:00Z&size=1" | jq -c .total
for _ in $(seq 1 40); do
  LINES=$(api ops-admin "/v1/ops/logs?correlationId=$CID" | tee "${TMPDIR:-/tmp}/cc-logs.json" | jq '.lines | length')
  [ "$LINES" -gt 0 ] && break
  sleep 3
done
jq -c '.lines[] | {timestamp, service, level, message}' "${TMPDIR:-/tmp}/cc-logs.json"
test "$LINES" -gt 0
api ops-admin /admin/generator/status | jq -c .
```

## Contracts and tooling

* `make api-docs` regenerates `application-api.yaml` and `decision-query-api.yaml` from the code (springdoc). `mvn verify` runs
  `OpenApiContractTest` in both services and fails when the committed file differs from the generated one, so CI catches drift.
  The files describe the services; the gateway adds the certificate, the role check, the quota and the spike arrest in front of them.
* `make lint-api` lints both files with Spectral (`.spectral.yaml`, rules `spectral:oas` plus a few stricter ones); it is part of `make verify` and CI.
* The event published by `POST /v1/applications` is checked against the committed JSON Schema twice: by the service before it publishes
  and by `SubmitContractTest`, which reads the record back from a real Kafka broker and validates it against `contracts/`.
* `make verify-mtls` checks the gateway and the bypass paths from the outside: [docs/security-mtls.md](../security-mtls.md).
