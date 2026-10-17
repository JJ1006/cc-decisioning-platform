# Sourced by deploy-ruleapp.sh and test-ruleset.sh: where RES is and how to talk to it.
# Override with env vars. ODM for Developers ships fixed demo users, not secrets.
ROOT=${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
RES_URL=${RES_URL:-https://localhost:9443}
RES_CA=${RES_CA:-$ROOT/security/pki/out/ca/ca.crt}
RES_USER=${RES_USER:-resAdmin}
RES_PASS=${RES_PASS:-$RES_USER}
RULEAPP=${RULEAPP:-ccApprovalApp}
RULEAPP_VERSION=${RULEAPP_VERSION:-1.0}
RULESET=${RULESET:-ccApproval}
RULESET_VERSION=${RULESET_VERSION:-1.0}

# res_curl <curl args...>: HTTPS with our CA; credentials via a curl config on stdin, not argv.
res_curl() {
  [[ -f "$RES_CA" ]] || { echo "CA not found: $RES_CA (run make pki)" >&2; return 2; }
  curl -sS --cacert "$RES_CA" -K <(printf 'user = "%s:%s"\n' "$RES_USER" "$RES_PASS") "$@"
}
