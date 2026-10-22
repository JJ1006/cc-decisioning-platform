# Limitations

Read this before you reuse anything. It says what the project is not.

## What this is

A portfolio-grade demonstration of an event-driven credit-card decisioning platform for a **fictitious** bank (Maple Northern Bank). It shows an architecture, a set of controls and the evidence for them. It is free to run on one laptop.

## What this is not

* **Not production software and not a product.** Nothing here has been penetration-tested, load-tested at production scale, or reviewed by a regulator. The compliance notes ([compliance](compliance.md)) map controls to expectations (PIPEDA, OSFI E-23, FCAC wording); they are not legal advice and not an attestation.
* **Not a real credit policy.** The rules in [rules-catalog](rules-catalog.md) are invented to exercise the machinery. They must not be used to decide credit for anyone. The score is part of the synthetic input; there is no bureau.
* **No real data.** Every applicant is generated. Do not load real personal data; the masking and retention design is demonstrated on synthetic values.
* **No AI decides.** An LLM may read, explain, simulate and draft. It has no tool to approve, decline, change a limit or record a manual decision ([ADR-0007](adr/0007-llm-never-decides.md)). That is a design property that the evals test (23 of 23 safety cases), not a promise about every possible model behaviour.

## IBM ODM licence

IBM ODM for Developers is **not open source**. It is for development and evaluation only; this repository contains no IBM binaries and never redistributes them. The image is pulled from IBM's registry at run time, you accept IBM's licence by running it, and release images exclude ODM ([ADR-0001](adr/0001-odm-dev-licence.md), [NOTICE](../NOTICE)). The developer image keeps its rules in a non-persistent Derby database, so an ODM restart loses them (`make up` redeploys; the chaos drill covers it). A production system needs a licensed ODM with a real database.

## Resources

* Designed for an 8 GB laptop, Docker Desktop VM 6.5 to 7 GB. Profiles exist because **everything at once does not fit**: `core` needs about 5.5 GB. kind and compose cannot run together. See the [profile table](../README.md#compose-profiles-8-gb-mac-never-run-everything-at-once).
* On Apple Silicon the ODM image is amd64 and runs under emulation, slowly. Cold starts take minutes.
* The load test passes at 20 requests per second and fails the 100 rps spike and the 2 s end-to-end threshold on this stack; the thresholds are for a sized environment ([evals](evals.md#latest-results-this-repository-docker-vm-67-gb-profilecoreanalyticsedge)).

## Security status

* One private CA made by `scripts/pki.sh`; keys are on your disk (gitignored). Rotation and revocation are not automated beyond `make cert-expiry` warnings and cert-manager on Kubernetes.
* Elasticsearch authenticates with passwords, not certificates: certificate login (PKI realm) needs a paid licence. kafka-ui has no login and listens on loopback only.
* The OWASP findings F-1 (any partner can read any application's status) and F-2 (no case assignment) are open ([backlog](backlog.md)).
* Kafka-side erasure waits for retention; dead letters keep an unmasked original for 7 days (crypto-shredding is designed, not built).
* OpenShift and the production Argo CD gate are rendered and linted, **never synced on a real cluster**.

### Known vulnerabilities in third-party images

`trivy image` (HIGH and CRITICAL, fixed upstream only, 2026-10-22) on the images the compose stack pulls: the observability and ELK images are demo-grade versions and carry findings that a newer tag fixes (counts, mostly Go and Java libraries inside upstream binaries):

| Image | HIGH + CRITICAL |
|---|---|
| prom/prometheus 3.2.1 | 108 |
| prom/alertmanager 0.28.1 | 97 |
| grafana/grafana-oss 11.5.2 | 149 |
| grafana/tempo 2.7.2 | 85 |
| otel/opentelemetry-collector-contrib 0.123.0 | 76 |
| prom/blackbox-exporter 0.26.0 | 52 |
| danielqsj/kafka-exporter 1.9.0, elasticsearch-exporter 1.8.0, logstash-exporter 1.8.2 | 48, 49, 27 |
| apache/kafka 3.9.2, apache/apisix 3.13.0 | 64, 32 |
| kafbat/kafka-ui 1.5.0 | 33 |
| Logstash, Kibana, Filebeat, Elasticsearch 8.19.23 | 26, 23, 5, 2 |
| IBM ODM 9.5.0.1 (not ours to patch) | 21 |

They run on a developer's machine, bound to loopback or an internal network, with synthetic data. That is the reason they are accepted, not a reason they are fine: the plan is one reviewed bump of the observability stack ([backlog](backlog.md)) and Dependabot proposes the compose image updates weekly. Our own images are scanned in CI on release and have no open fixable HIGH or CRITICAL finding except the two accepted Spring CVEs in `.trivyignore` (expires 2026-12-31).

## Not covered

French-language notices are unreviewed. Accessibility of the dashboards is whatever Kibana and Grafana give. There is no disaster recovery, no multi-region, no backup of Elasticsearch or Kafka beyond what the volumes hold.
