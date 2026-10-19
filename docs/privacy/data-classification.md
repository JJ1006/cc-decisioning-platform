# Data classification, retention and privacy requests (P12)

Maple Northern Bank is fictitious and every record is synthetic. This is the classification the platform would need for real data, kept honest for the
demo: it says what each field is, where it lives, how long, and who can read it. It is a design document, not legal advice
([compliance](../compliance.md)).

## Classes

| Class | Meaning | Examples | Rule |
|---|---|---|---|
| **DI** Direct identifier | names a person, or with little effort finds them | first and last name, SIN, date of birth, street line, postal code, employer name, phone, e-mail | Never indexed, never in an event after the first hop. Masked by Logstash (SIN dropped, names to initials, postal code to FSA, date of birth to age band, street and employer dropped). Short retention where it exists at all |
| **SF** Sensitive financial | reveals a person's finances, not who they are | annual income, monthly debt, housing cost, bureau score, TDS, bankruptcy flag, delinquencies, requested and granted limit | Restricted: the full set is in `cc-decision-inputs`, readable by the query API only (underwriter and agent roles through it). Dashboards get bands |
| **PS** Pseudonym | links records of one applicant without naming them | `applicantToken` = HMAC-SHA-256 of the applicant id, first 128 bits | Key in the orchestrator's environment only (`APPLICANT_TOKEN_KEY`); with the key an erasure or access request finds the applicant, without it nothing can |
| **DB** Derived band | a coarse value made from SF/DI so groups can be counted | `ageBand`, `scoreBand`, `tdsBand`, `limitBand`, province, FSA of a masked postal code | Analyst-readable. Bands of at least 30 decisions only in fairness findings |
| **OP** Operational | needed to run the system | event ids, correlation ids, outcome, reason codes, fired rules, ruleset path and version, topics, offsets, log lines | Readable by operators and analysts. Free text in it (notes, log messages) is treated as untrusted |
| **AC** Accountability | proves who did what | audit records, MCP tool calls | No argument values; erasure records carry a hash of the application id, not the id |

## What lives where

### Kafka (retention by class, `docs/kafka-topics.md`, generated into the Strimzi `KafkaTopic`s)

| Topic | Fields | Classes | Retention |
|---|---|---|---|
| `cc.applications.submitted.v1` | the whole application: applicant (id, names, date of birth, age, SIN, residency), address (street, city, province, postal code), employment (status, employer, income, months), bureau (score, debt, limit, delinquencies, inquiries, bankruptcy, file age), requested product and limit | DI + SF | 7 days |
| `cc.decisions.{approved,declined,referred}.v1` | outcome, limit, product, APR tier, reason codes, fired rules, ruleset version, **ruleset path, deployment time**, applicant summary (province, age band, score, TDS, income), **inputs** (age, province, residency, employment status, income, months at employer, housing cost, monthly debt, TDS, score, bankruptcy flag, delinquencies, inquiries, file age, existing limit, requested limit and product, applicant token) | SF + PS + DB + OP | 7 days |
| `cc.decisions.manual.v1` | application id, outcome, limit, product, reason, underwriter id, **note (free text)** | OP (+ the note may say anything) | 30 days |
| `cc.notifications.v1` | application id, notice type, channel, opaque recipient reference, subject, body (bilingual, reasons in plain words) | OP | 7 days |
| `cc.audit.v1` | actor, action, resource, result, details (argument names and a hash for tool calls) | AC | 30 days |
| `<topic>.retry` | the record being retried | as the topic | 1 day |
| `<topic>.dlq` | the unmasked original record, error class and message | DI + SF | **7 days** (was 30: it keeps what the masking removes) |

### Elasticsearch (masked by Logstash before indexing; retention by ILM, `deploy/es`)

| Index | Content | Class | Read by | Retention (demo / suggested production) |
|---|---|---|---|---|
| `cc-decisions` | decision documents, masked; bands added; `inputs` removed | OP + DB (+ applicant summary: score, TDS, income, a known gap) | analysts, ops, query API | 30 d (`cc-demo-30d`) / the retention of the model-risk policy, typically 7 years |
| `cc-decision-inputs` | the full inputs and the applicant token | **SF + PS** | **query API only**; no Kibana role has it | 90 d (`cc-inputs-90d`, rolled daily) / the shortest period the decision can still be explained or disputed |
| `cc-notifications` | the notice sent | OP | analysts, ops | 30 d / as the decision |
| `cc-audit` | audit records, tool calls, erasure and export records | AC | analysts, ops | 30 d / at least 1 year (7 years for the decision trail) |
| `cc-dlq` | dead letters, masked or redacted copy, cut to 1,024 characters | OP | ops | 30 d (`cc-dlq-30d`) |
| `cc-logs` | log lines of our services (no personal data is logged; free text in messages is untrusted) | OP | ops | 7 d (`cc-logs-7d`) |
| `cc-alerts` | alert documents | OP | ops | none yet (small) |

**Why a separate inputs index.** Elasticsearch field-level security needs a paid licence. A separate index with its own role does the same job on the free
tier: the roles `kibana_analyst`, `kibana_ops` and `grafana_logs_reader` list the indices they may read and none of them lists `cc-decision-inputs`;
`make verify-mtls` proves it (403 for `analyst` and `ops`, also by wildcard). Only `query_reader` (the query API) and `logstash_writer` (create only) have it.

### Other places

| Place | What | Note |
|---|---|---|
| Decision Center / RES (ODM) | rules; RES receives the application without SIN and date of birth | the Derby database of the dev image holds the rule projects and execution traces of a lab |
| Logs of the services | correlation ids, application ids, outcome | no names, no SIN (checked by the masking tests) |
| MCP servers | nothing stored; output masked (Presidio) and wrapped | audit only |

## Retention in short

Demo values are short on purpose; production values are decisions for the bank's privacy and risk officers, not engineering. The mechanism is per class: the Kafka
retention (generated into the `KafkaTopic`s), the ILM policies (`cc-logs-7d`, `cc-dlq-30d`, `cc-inputs-90d`, `cc-demo-30d`) and the erasure endpoint below.

## Access and erasure requests (PIPEDA principle 4.9, 4.5.3)

Role **OPS** only, through the gateway, with an audit record each time:

```bash
scripts/api-call.sh ops-admin /v1/ops/privacy/export/APP-00000986                  # everything stored about the application, by data set
scripts/api-call.sh ops-admin -X POST -H 'Content-Type: application/json' \
  --data '{"applicationId":"APP-00000986"}' /v1/ops/privacy/erase               # delete_by_query across the cc-* indices
```

* **Export** returns the decision, the inputs (with the applicant token), the notifications, the audit records, the dead letters and the log lines that carry the application id,
  as stored (already masked), and writes `PRIVACY_EXPORT`.
* **Erase** deletes the same documents and writes `PRIVACY_ERASURE` with who asked, how many documents per data set, and `sha256:<16 hex>` of the id as the resource,
  so the record of the erasure does not bring the application back. Elasticsearch is erased immediately.
* The endpoints use a second Elasticsearch user, `privacy-ops` (role `privacy_ops`: read and delete on the cc-* data, create on the audit alias). It is used by these two
  endpoints only; if its password is not set they answer 503. Production moves them into a service of their own so the read-only query API never holds a delete credential.
* The applicant token finds the other applications of the same applicant (a search by token in `cc-decision-inputs`); erasing a person, not an application, is a loop
  over them (backlog).

### Kafka and crypto-shredding

Deleting a record from a Kafka topic is not possible; the topics expire by retention (7 to 30 days) and compacted or longer-lived topics would need more. The production
answer is **crypto-shredding**: encrypt the personal fields of each event with a per-applicant data key held in a key service (the applicant token indexes it), and erase the
key on request. The ciphertext in Kafka, in backups and in the dead letters becomes noise. Not built here: the key service, the envelope encryption in the producers and
the decryption in the consumers are a phase of their own ([backlog](../backlog.md)). Until then, a Kafka-side erasure waits for retention (documented to the requester).

## Masking

Logstash (`deploy/logstash/ruby/cc_mask.rb`) removes or reduces the direct identifiers of every document before it is indexed; the MCP server masks again on the way out
(Presidio patterns for SIN with the Luhn check, postal code, e-mail, phone, card, plus fields by name). Names inside free text cannot be found by patterns, so free text is
wrapped as untrusted data instead of being trusted as clean ([threat model](../security/threat-model.md)).
