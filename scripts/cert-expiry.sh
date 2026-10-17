#!/usr/bin/env bash
# Days left on every certificate of the demo PKI (CA, services, clients). Warns below 14 days, fails on expired ones.
#   scripts/cert-expiry.sh [--days N]       (make cert-expiry)
# Leaf certificates last 825 days, the CA 3650 (scripts/pki.sh). Rotation: docs/security-mtls.md.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PKI=${PKI_OUT:-$ROOT/security/pki/out}
WARN_DAYS=14
[[ "${1:-}" == --days ]] && WARN_DAYS=${2:?--days needs a number}
[[ -d "$PKI" ]] || { echo "no PKI at $PKI (run make pki)" >&2; exit 2; }

python3 - "$PKI" "$WARN_DAYS" <<'PY'
import glob
import os
import ssl
import subprocess
import sys
import time

pki, warn = sys.argv[1], int(sys.argv[2])
files = sorted(
    f
    for f in glob.glob(os.path.join(pki, "**", "*.crt"), recursive=True)
    if not f.endswith("-chain.crt")
)
now = time.time()
worst = 0
for f in files:
    out = subprocess.run(["openssl", "x509", "-in", f, "-noout", "-enddate"], capture_output=True, text=True, check=True).stdout
    end = ssl.cert_time_to_seconds(out.strip().split("=", 1)[1])
    days = int((end - now) // 86400)
    name = os.path.relpath(f, pki)
    if days < 0:
        status, worst = "EXPIRED", 2
    elif days < warn:
        status, worst = "WARN   ", max(worst, 1)
    else:
        status = "ok     "
    print(f"{status} {days:5d} days  {name}")
print(f"{len(files)} certificates, warning below {warn} days")
sys.exit(worst)
PY
