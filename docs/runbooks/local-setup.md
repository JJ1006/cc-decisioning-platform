# Local setup (Docker Compose)

Everything runs with Docker Compose on one machine. All images are multi-arch, so on Apple Silicon they run **natively (arm64), no emulation** — including IBM ODM 9.5.

## Prerequisites

```bash
brew install openjdk@21 maven uv jq gh pre-commit gitleaks
make bootstrap      # checks tools, Docker memory, Rosetta
```

Docker Desktop: **Settings → Resources → Memory ≥ 6 GB** (8 GB Mac: 6.5 GB). `make bootstrap` fails below 6 GB.
Rosetta is only needed for amd64-only images (none in the current stack), so a warning there is harmless.

## Start

```bash
cp .env.example .env        # created automatically by make up; edit the placeholder passwords
make pki                    # private CA + certs in security/pki/out (gitignored, idempotent)
make up PROFILE=core,analytics
make verify-mtls            # TLS / mTLS checks (also: make kafka-topics-mtls)
```

`make up PROFILE=core` is the minimum. Profiles are additive; `analytics` needs `core`.
Do not run everything at once on an 8 GB Mac (see the README profile table).
`make down` stops everything, `make down V=1` also deletes data volumes, `make logs S=kafka`, `make ps`.

## Endpoints and credentials (dev only)

| What | URL (host) | Login |
|---|---|---|
| ODM Decision Center (client certificate required: import `security/pki/out/admin/admin.p12`, see [security-mtls](../security-mtls.md)) | <https://localhost:9443/decisioncenter> | `odmAdmin` / `odmAdmin` |
| ODM Rule Execution Server console | <https://localhost:9443/res> | `resAdmin` / `resAdmin` |
| ODM Decision Runner | <https://localhost:9443/DecisionRunner> | `odmAdmin` / `odmAdmin` |
| Grafana (metrics, traces; `make up-obs`, [docs/observability.md](../observability.md)) | <https://localhost:3000> | `admin` / `GRAFANA_ADMIN_PASSWORD` from the root env file |
| Prometheus, Alertmanager (`obs` profile) | <http://localhost:9090>, <http://localhost:9097> | none (loopback only) |
| Kibana (space Maple Northern, [docs/kibana.md](../kibana.md)) | <https://localhost:5601/s/maple-northern> | `analyst` / `ANALYST_PASSWORD`, `ops` / `OPS_PASSWORD`, admin `elastic` / `ELASTIC_PASSWORD`, all from the root env file |
| kafka-ui | <http://localhost:8080> | none (bound to 127.0.0.1) |
| Elasticsearch | <https://localhost:9200> | `elastic` / `ELASTIC_PASSWORD` |
| Kafka (mTLS) | `localhost:9093` | client certificate required |

The browser will warn about the certificate until you trust `security/pki/out/ca/ca.crt` (import it into your keychain, or use `curl --cacert`). ODM's demo users are fixed by IBM's image; they are not secrets.

## What runs

| Service | Profile | Notes |
|---|---|---|
| `kafka` | core | Apache Kafka 3.9 KRaft, single node. Only SSL listeners: `kafka:9093` (containers) and `localhost:9093` (host). `ssl.client.auth=required`. The KRaft controller listener is plaintext on an unpublished port inside the container. No `PLAINTEXT` listener exists. |
| `topic-init` | core | One-shot job: creates all topics from `docs/kafka-topics.md` plus `.retry`/`.dlq` (21 topics) via `scripts/create-topics.sh`. |
| `elasticsearch` | core | 8.19, security on, TLS on HTTP and transport, client certificates optional. Heap 512 MB. |
| `es-setup` | core | One-shot job: sets the `kibana_system` password. |
| `odm` | core | ODM for Developers all-in-one (Decision Center + RES + Decision Runner), served over HTTPS with our `odm` certificate. Liberty listens on 9453 inside the container; the host port stays 9443. |
| `kibana` | analytics | HTTPS, TLS to Elasticsearch, `kibana_system` account. |
| `kafka-ui` | analytics | mTLS to Kafka using the `kafka-ui` client certificate. |

Memory limits are set on every container. Measured with `core,analytics` idle on Apple Silicon: Kafka ~0.25 GB, Elasticsearch ~0.85 GB, ODM ~0.95 GB (peaks ~1.4 GB while starting), Kibana ~0.7 GB, kafka-ui ~0.3 GB — about 3 GB in total. That fits in a 4 GB Docker VM, but later phases add ~10 Java services, so keep Docker at 6 GB or more.

## PKI

`scripts/pki.sh` creates one root CA and one certificate per component (server + client auth; SANs: component name, `localhost`, `127.0.0.1`) plus pure client certificates (`admin`, `demo-partner`, `underwriter-portal`, `mcp`).
Per component: `security/pki/out/<name>/<name>.{crt,key,p12,jks}`; shared: `ca/ca.crt`, `truststore.p12|jks`, `kafka-client.properties`.
`make pki FORCE=1 CONFIRM=1` wipes and regenerates everything, including the CA. Without `CONFIRM=1` it only prints a warning: Kafka and Elasticsearch state made under the old CA stops working, so run `make down V=1` afterwards and `make up` again.

Dev trade-offs: leaf keys/keystores are world-readable so non-root container users can read bind mounts; the CA key (`ca-private/`) is `0600` and is never mounted. Kubernetes uses cert-manager instead (Phase 10).

## Claude Code guardrail note

The `pre_tool_guard` hook blocks commands that touch `*.key`, `*.p12`, `*.jks`, `.env*` or `security/pki/out/**` (public `*.crt` files are allowed). Run anything that needs certificates through `make` targets or scripts: `make verify-mtls`, `make kafka-topics-mtls`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `bootstrap: Docker memory 3.8 GB < 6 GB` | Docker Desktop → Settings → Resources → Memory → 6.5 GB, Apply & Restart |
| Container `Exited (137)` | Out of memory. Raise Docker memory, or run fewer profiles; ODM needs ~2 GB (`ODM_MEM` in `.env`) |
| ODM `unhealthy` for the first minutes | Normal: it initialises an embedded database, allow ~2–3 min (`start_period: 120s`) |
| Elasticsearch `failed to load SSL configuration ... access ... blocked` | ES only reads certificates from its config dir; the compose file mounts them under `/usr/share/elasticsearch/config/certs`. Do not move them. |
| Kafka client `SSL handshake failed` | You are connecting without a client certificate, or with one not signed by this CA. Use the `admin` certificate (`kafka-client.properties`). |
| Kafka container dies with `file does not exist` / `KAFKA_SSL_KEYSTORE_FILENAME` | A listener name ends in `SSL`; the image then expects file-based secrets. Keep names `INTERNAL`/`EXTERNAL`. |
| Browser: `NET::ERR_CERT_AUTHORITY_INVALID` | Trust `security/pki/out/ca/ca.crt` in your OS keychain (dev only) |
| Changed `PKI_PASSWORD` | `make pki FORCE=1 CONFIRM=1`, `make down V=1`, `make up ...` |

## Kubernetes

`make kind-up PROFILE=lite` runs the platform on a local kind cluster instead of compose (stop compose first: `make down V=1`). See [deploy-kubernetes](../deploy-kubernetes.md).
