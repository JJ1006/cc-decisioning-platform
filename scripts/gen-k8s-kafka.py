#!/usr/bin/env python3
"""Generate the Kafka topics and users (with ACLs) of the Strimzi chart and the ACLs of the compose broker from the docs - no hand copy.

  docs/kafka-topics.md          -> topics (business topics + <topic>.retry + <topic>.dlq, like scripts/create-topics.sh)
  docs/security-mtls.md         -> users and ACLs (table "Kafka clients and ACLs")

Output: deploy/helm/cc-infra/files/kafka.yaml (read by templates/kafka-topics.yaml and kafka-users.yaml) and
        deploy/compose/kafka-acls.args (one kafka-acls.sh argument line per rule group; applied by the acl-init container, principal = CN).
  gen-k8s-kafka.py           write the files
  gen-k8s-kafka.py --check   exit 1 when a committed file is stale (CI)
"""

from __future__ import annotations

import fnmatch
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOPICS_DOC = ROOT / "docs" / "kafka-topics.md"
ACL_DOC = ROOT / "docs" / "security-mtls.md"
OUT = ROOT / "deploy" / "helm" / "cc-infra" / "files" / "kafka.yaml"
ARGS_OUT = ROOT / "deploy" / "compose" / "kafka-acls.args"

# the table has no machine-readable form for these two, and the doc says so in words
OPERATORS = {
    "kafka-ui": {
        "topics_read": ["*"],
        "groups": ["*"],
    },  # "all topics, describe": a read-only operator tool
    "admin": {"topics_admin": True},  # "create, alter, describe topics and groups"
    "kafka-exporter": {
        "describe_all": True
    },  # metrics: topic and group offsets, no data
}


def ticks(cell: str) -> list[str]:
    return re.findall(r"`([^`]+)`", cell)


def expand(token: str) -> list[str]:
    m = re.search(r"\{([^}]+)\}", token)
    if not m:
        return [token]
    return [
        x
        for part in m.group(1).split(",")
        for x in expand(token[: m.start()] + part + token[m.end() :])
    ]


def topics() -> list[dict]:
    rows = []
    for line in TOPICS_DOC.read_text().splitlines():
        m = re.match(r"\|\s*`(cc\.[^`]+)`\s*\|[^|]*\|\s*(\d+)\s*\|\s*(\d+)d\s*\|", line)
        if m:
            rows.append(
                {
                    "name": m.group(1),
                    "partitions": int(m.group(2)),
                    "retentionDays": int(m.group(3)),
                }
            )
    out = []
    for t in rows:
        out.append(t)
        out.append({"name": t["name"] + ".retry", "partitions": 3, "retentionDays": 1})
        out.append({"name": t["name"] + ".dlq", "partitions": 1, "retentionDays": 7})
    return out


def table_rows() -> list[list[str]]:
    text = ACL_DOC.read_text()
    section = text.split("## Kafka clients and ACLs", 1)[1].split("\n## ", 1)[0]
    rows = []
    for line in section.splitlines():
        if line.startswith("| `"):
            rows.append([c.strip() for c in line.strip().strip("|").split("|")])
    return rows


def acl(rtype: str, name: str, ops: list[str], pattern: str = "literal") -> dict:
    return {
        "resource": {"type": rtype, "name": name, "patternType": pattern},
        "operations": ops,
    }


def users(all_topics: list[str]) -> list[dict]:
    result = []
    for cols in table_rows():
        client = ticks(cols[0])[0]
        reads, writes, other = (cols + ["", "", ""])[1:4]
        acls: list[dict] = []
        read_topics: list[str] = []
        write_topics: list[str] = []
        groups: list[str] = []
        for seg in reads.split(";"):
            names = ticks(re.sub(r"\(.*?\)", "", seg))
            grp = re.search(r"\(`([^`]+)`\)", seg)
            for tok in names:
                for pat in expand(tok):
                    read_topics += (
                        [t for t in all_topics if fnmatch.fnmatchcase(t, pat)]
                        if "*" in pat
                        else [pat]
                    )
            if grp:
                groups.append(grp.group(1))
        for tok in ticks(writes):
            for pat in expand(tok):
                if pat.startswith(
                    "."
                ):  # ".dlq": the dead letter topics of what this client reads
                    write_topics += [t + pat for t in read_topics]
                else:
                    write_topics.append(pat)
        if (
            client in groups
        ):  # a consuming service: non-blocking retry topics and dead letters of what it consumes
            for t in list(read_topics):
                acls.append(acl("topic", t + ".retry", ["Read", "Write", "Describe"]))
                write_topics.append(t + ".dlq")
        for t in dict.fromkeys(read_topics):
            acls.append(acl("topic", t, ["Read", "Describe"]))
        for t in dict.fromkeys(write_topics):
            acls.append(acl("topic", t, ["Write", "Describe"]))
        for g in groups:
            acls.append(
                acl(
                    "group",
                    g,
                    ["Read"],
                    "prefix" if client != "logstash" else "literal",
                )
            )
        m = re.search(r"transactional id prefix `([^`]+)`", other)
        if m:
            acls.append(
                acl("transactionalId", m.group(1), ["Describe", "Write"], "prefix")
            )
        op = OPERATORS.get(client)
        if op and op.get("topics_read"):
            acls.append(acl("topic", "*", ["Read", "Describe"]))
            acls.append(acl("group", "*", ["Read", "Describe"]))
        if op and op.get("describe_all"):
            acls.append(acl("topic", "*", ["Describe"]))
            acls.append(acl("group", "*", ["Describe"]))
        if op and op.get("topics_admin"):
            acls.append(acl("topic", "*", ["Create", "Alter", "Describe", "Read"]))
            acls.append(acl("group", "*", ["Describe", "Read"]))
        result.append({"name": client, "acls": acls})
    return result


def render() -> str:
    ts = topics()
    us = users([t["name"] for t in ts])
    lines = [
        "# GENERATED by scripts/gen-k8s-kafka.py from docs/kafka-topics.md and docs/security-mtls.md - do not edit",
        "topics:",
    ]
    for t in ts:
        lines.append(
            f"  - {{ name: {t['name']}, partitions: {t['partitions']}, retentionDays: {t['retentionDays']} }}"
        )
    lines.append("users:")
    for u in us:
        lines.append(f"  - name: {u['name']}")
        lines.append("    acls:")
        for a in u["acls"]:
            r = a["resource"]
            lines.append(
                f'      - {{ type: {r["type"]}, name: "{r["name"]}", patternType: {r["patternType"]}, operations: [{", ".join(a["operations"])}] }}'
            )
        if not u["acls"]:
            lines[-1] = "    acls: []"
    return "\n".join(lines) + "\n"


def render_args() -> str:
    """kafka-acls.sh arguments, one line per (client, resource type, pattern, operations) with all its resource names.
    The compose broker maps the certificate subject to its CN (ssl.principal.mapping.rules), so the principal is User:<CN>."""
    flag = {
        "topic": "--topic",
        "group": "--group",
        "transactionalId": "--transactional-id",
    }
    lines = [
        "# GENERATED by scripts/gen-k8s-kafka.py from docs/security-mtls.md - do not edit",
        "# one kafka-acls.sh --add argument line per rule group (applied by the acl-init service, shell globbing off)",
    ]
    for u in users([t["name"] for t in topics()]):
        groups: dict[tuple, list[str]] = {}
        for a in u["acls"]:
            r = a["resource"]
            groups.setdefault(
                (r["type"], r["patternType"], tuple(a["operations"])), []
            ).append(r["name"])
        for (rtype, pattern, ops), names in groups.items():
            parts = [f"--allow-principal User:{u['name']}"]
            parts += [f"--operation {o}" for o in ops]
            if pattern == "prefix":
                parts.append("--resource-pattern-type prefixed")
            parts += [f"{flag[rtype]} {n}" for n in names]
            lines.append(" ".join(parts))
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    outputs = {OUT: render(), ARGS_OUT: render_args()}
    if "--check" in sys.argv:
        stale = [
            p for p, text in outputs.items() if not p.exists() or p.read_text() != text
        ]
        for p in stale:
            print(
                f"{p.relative_to(ROOT)} is stale: run scripts/gen-k8s-kafka.py",
                file=sys.stderr,
            )
        if stale:
            sys.exit(1)
        print("kafka topics, users and ACLs are up to date")
    else:
        for p, text in outputs.items():
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
            print(f"wrote {p.relative_to(ROOT)}")
