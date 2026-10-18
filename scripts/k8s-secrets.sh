#!/usr/bin/env bash
# Secrets for a local kind cluster, made from the compose secrets (the root env file and its derived file) or generated. NOTHING secret is
# in git and nothing is printed. On OpenShift or in production use Sealed Secrets / External Secrets instead (docs/deploy-kubernetes.md):
# the secrets this creates and who reads them are listed there, name for name.
#   scripts/k8s-secrets.sh KUBE-CONTEXT [NAMESPACE=cc] [lite|full]
#
#   cert-manager ns  cc-ca-keypair      the platform CA of `make pki` (certificate + private key): the ClusterIssuer cc-ca signs with it
#   NAMESPACE        cc-pki-password    password of every cert-manager PKCS12/JKS store
#                    cc-odm-credentials the ODM for Developers demo users (RES_*, DC_*)   - public demo defaults, not secrets
#                    cc-es-passwords    passwords of the Elasticsearch users es-setup creates (full)
#                    cc-kibana-keys     Kibana encryption key, key "encryption" (full)
#                    cc-grafana, cc-grafana-env   Grafana admin and the logs-reader password (full)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CTX=${1:?kube context}
NS=${2:-cc}
PROFILE=${3:-lite}
PKI=${PKI_OUT:-$ROOT/security/pki/out}
k() { kubectl --context "$CTX" "$@"; }

[[ -f "$PKI/ca/ca.crt" && -f "$PKI/ca-private/ca.key" ]] || { echo "NEED USER: run make pki (the platform CA is missing)"; exit 2; }

# values from the compose env when they exist (so both stacks share the demo passwords), else generated
DERIVED=$ROOT/deploy/compose/.env.derived
get() { # get NAME -> value from the derived env file, or empty
  [[ -f "$DERIVED" ]] && grep -E "^$1=" "$DERIVED" | tail -1 | cut -d= -f2- || true
}
val() { # val NAME -> existing value, else a new random hex string
  local v
  v=$(get "$1")
  [[ -n "$v" ]] && echo "$v" || openssl rand -hex 24
}
exists() { k -n "$1" get secret "$2" >/dev/null 2>&1; }

# the namespace: Pod Security "restricted" (what OpenShift's restricted-v2 enforces) except in full, where Filebeat reads the node's logs
k create namespace "$NS" --dry-run=client -o yaml | k apply -f - >/dev/null
if [[ "$PROFILE" == full ]]; then
  k label namespace "$NS" pod-security.kubernetes.io/enforce=privileged pod-security.kubernetes.io/warn=restricted --overwrite >/dev/null
else
  k label namespace "$NS" pod-security.kubernetes.io/enforce=restricted pod-security.kubernetes.io/warn=restricted --overwrite >/dev/null
fi

k create namespace cert-manager --dry-run=client -o yaml | k apply -f - >/dev/null
k -n cert-manager create secret tls cc-ca-keypair --cert="$PKI/ca/ca.crt" --key="$PKI/ca-private/ca.key" --dry-run=client -o yaml | k apply -f - >/dev/null
echo "cert-manager/cc-ca-keypair  (the CA of make pki)"

if ! exists "$NS" cc-pki-password; then
  k -n "$NS" create secret generic cc-pki-password --from-literal=password="$(val KEYSTORE_PASSWORD)" >/dev/null
  echo "$NS/cc-pki-password"
fi
# the demo users of the ODM for Developers image are fixed by IBM (public, in its documentation): they are defaults here, not secrets
RESU=$(get RES_USERNAME)
DCU=$(get DC_USERNAME)
RESU=${RESU:-resAdmin}
DCU=${DCU:-odmAdmin}
RESP=$(get RES_PASSWORD)
DCP=$(get DC_PASSWORD)
k -n "$NS" create secret generic cc-odm-credentials \
  --from-literal=RES_USERNAME="$RESU" --from-literal=RES_PASSWORD="${RESP:-$RESU}" \
  --from-literal=DC_USERNAME="$DCU" --from-literal=DC_PASSWORD="${DCP:-$DCU}" \
  --dry-run=client -o yaml | k apply -f - >/dev/null
echo "$NS/cc-odm-credentials"

if [[ "$PROFILE" == full ]]; then
  if ! exists "$NS" cc-es-passwords; then
    args=()
    for n in KIBANA_PASSWORD LOGSTASH_WRITER_PASSWORD QUERY_API_PASSWORD ANALYST_PASSWORD OPS_PASSWORD OBS_EXPORTER_PASSWORD GRAFANA_ES_PASSWORD; do
      args+=("--from-literal=$n=$(val "$n")")
    done
    k -n "$NS" create secret generic cc-es-passwords "${args[@]}" >/dev/null
    echo "$NS/cc-es-passwords"
  fi
  if ! exists "$NS" cc-kibana-keys; then
    k -n "$NS" create secret generic cc-kibana-keys --from-literal=encryption="$(openssl rand -hex 32)" >/dev/null
    echo "$NS/cc-kibana-keys"
  fi
  if ! exists "$NS" cc-grafana; then
    k -n "$NS" create secret generic cc-grafana --from-literal=admin-user=admin --from-literal=admin-password="$(val GRAFANA_ADMIN_PASSWORD)" >/dev/null
    echo "$NS/cc-grafana"
  fi
  gp=$(k -n "$NS" get secret cc-es-passwords -o jsonpath='{.data.GRAFANA_ES_PASSWORD}' | base64 -d)
  k -n "$NS" create secret generic cc-grafana-env --from-literal=GRAFANA_ES_PASSWORD="$gp" --dry-run=client -o yaml | k apply -f - >/dev/null
fi
