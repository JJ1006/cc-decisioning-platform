#!/usr/bin/env bash
# Kubernetes MCP (containers/kubernetes-mcp-server, pinned) over stdio. OPTIONAL: needs the kind cluster "cc" (make kind-up PROFILE=lite).
# Read only (--read-only) and limited to the kind cluster: the kubeconfig is exported from kind for that one cluster, so no other context
# (for example a production one in ~/.kube/config) is ever visible to the server.
set -euo pipefail
VERSION=0.0.67
if ! command -v kind >/dev/null || ! kind get clusters 2>/dev/null | grep -qx cc; then
  echo "kubernetes mcp: the kind cluster 'cc' is not running (make kind-up PROFILE=lite); this server is optional" >&2
  exit 1
fi
KUBECONFIG_FILE=$(mktemp "${TMPDIR:-/tmp}/kind-cc-kubeconfig.XXXXXX")
trap 'rm -f "$KUBECONFIG_FILE"' EXIT
kind get kubeconfig --name cc >"$KUBECONFIG_FILE"
chmod 600 "$KUBECONFIG_FILE"
npx -y "kubernetes-mcp-server@$VERSION" --read-only --cluster-provider kubeconfig --kubeconfig "$KUBECONFIG_FILE"
