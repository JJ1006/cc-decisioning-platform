#!/usr/bin/env python3
"""Add @sha256:<digest> to every third-party image reference (tag stays, for people; the digest is what is pulled).

  scripts/pin-digests.py          rewrite the files
  scripts/pin-digests.py --list   print what would change

Covers deploy/compose/docker-compose.yml, the Dockerfiles, and the Helm values (`repository:` + `tag:` pairs, and `image:` lines).
Our own images (ccplatform/*:dev built locally, jj1006/cc-* published by images.yml) are not pinned here; the release pins them by tag + signature.
Digest = what `docker buildx imagetools inspect` prints (the index digest for multi-arch images). Needs Docker and network.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OURS = ("ccplatform/", "jj1006/", "ghcr.io/jj1006/")
cache: dict[str, str] = {}


def digest(ref: str) -> str:
    if ref not in cache:
        out = subprocess.run(
            ["docker", "buildx", "imagetools", "inspect", ref],
            check=False,
            capture_output=True,
            text=True,
        )
        m = re.search(r"^Digest:\s+(sha256:[0-9a-f]{64})", out.stdout, re.MULTILINE)
        if not m:
            sys.exit(f"cannot resolve {ref}: {out.stderr.strip()[:200]}")
        cache[ref] = m.group(1)
    return cache[ref]


def pin(ref: str) -> str:
    if "@sha256:" in ref or ref.startswith(OURS) or "{{" in ref or ref.endswith(":dev"):
        return ref
    return f"{ref}@{digest(ref)}"


files = [ROOT / "deploy/compose/docker-compose.yml"]
files += [
    ROOT / p
    for p in subprocess.run(
        ["git", "ls-files", "*Dockerfile*", "deploy/helm/**/values*.yaml"],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    ).stdout.split()
]
changed = 0
for f in files:
    text = f.read_text()
    out = []
    lines = text.split("\n")
    for i, line in enumerate(lines):
        m = re.match(r"^(\s*(?:-\s+)?image:\s+)([^\s#{]+)(.*)$", line) or re.match(
            r"^(\s*FROM\s+(?:--platform=\S+\s+)?)(\S+)(.*)$", line
        )
        if m and "$" not in m.group(2) and not m.group(2).startswith("{"):
            ref = m.group(2)
            if ref.count(":") >= 1 and not ref.startswith("scratch"):
                line = m.group(1) + pin(ref) + m.group(3)
        m = re.match(r"^(\s*tag:\s+)(\S+)(.*)$", line)
        if m and i > 0:
            # a tag next to a third-party repository: line within 3 above
            ctx = "\n".join(lines[max(0, i - 3) : i])
            r = re.findall(r"repository:\s+(\S+)", ctx)
            if (
                r
                and not r[-1].startswith(OURS)
                and "@sha256:" not in m.group(2)
                and "{{" not in m.group(2)
            ):
                full = f"{r[-1]}:{m.group(2)}"
                line = m.group(1) + pin(full).split(r[-1] + ":", 1)[1] + m.group(3)
        out.append(line)
    new = "\n".join(out)
    if new != text:
        changed += 1
        print("pin", f.relative_to(ROOT))
        if "--list" not in sys.argv:
            f.write_text(new)
print(f"{changed} file(s)", "would change" if "--list" in sys.argv else "changed")
