# CertificateExpiringSoon

Severity: warning. Rule: `observability/prometheus/rules/alerts.yml`. Alertmanager UI: <http://localhost:9097>. Overview: [docs/observability.md](../../observability.md).

## What fired

The certificate presented by the gateway, ODM or Elasticsearch expires in under 14 days (`probe_ssl_earliest_cert_expiry`, blackbox-exporter).

## Impact

When it expires every client of that endpoint fails the TLS handshake.

## Look at

1. `make cert-expiry` (scripts/cert-expiry.sh) lists the days left on every certificate of the demo PKI.
2. The alert labels name the endpoint; the rotation steps are in docs/security-mtls.md.

## Fix

* Reissue and restart: delete the component's folder under `security/pki/out/` and run `make pki` (or `make pki FORCE=1` for everything), then restart the affected containers and run `make verify-mtls`.
