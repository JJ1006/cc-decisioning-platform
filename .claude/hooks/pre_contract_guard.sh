#!/usr/bin/env bash
# PreToolUse (Edit|Write): a breaking change of contracts/*.schema.json needs a new version (docs/data-model.md: the event type ends in .vN, a breaking
# change gets .v(N+1) and a new topic). Compares the file on disk with what the tool is about to write. A breaking change is a property removed,
# a new required field, a narrowed enum, a changed type or const. WARNS (does not block): the warning goes to Claude as context; additive changes pass.
set -uo pipefail
in=$(cat)
tool=$(jq -r '.tool_name // empty' <<<"$in")
[[ "$tool" == Edit || "$tool" == Write ]] || exit 0
f=$(jq -r '.tool_input.file_path // empty' <<<"$in")
case "$f" in */contracts/*.schema.json | contracts/*.schema.json) ;; *) exit 0 ;; esac
[[ -f "$f" ]] || exit 0 # a new schema is version 1

python3 -I - "$f" "$in" <<'PY'
import json, re, sys

path, hook = sys.argv[1], json.loads(sys.argv[2])
ti = hook["tool_input"]
old_text = open(path).read()
if hook["tool_name"] == "Write":
    new_text = ti.get("content", "")
else:
    old_s, new_s = ti.get("old_string", ""), ti.get("new_string", "")
    if old_s not in old_text:
        sys.exit(0)  # the edit will fail by itself
    new_text = old_text.replace(old_s, new_s) if ti.get("replace_all") else old_text.replace(old_s, new_s, 1)
try:
    old, new = json.loads(old_text), json.loads(new_text)
except ValueError:
    sys.exit(0)  # not valid JSON after the edit: the post hook reports that


def version(doc):
    m = re.search(r"\.v(\d+)\"", json.dumps(doc.get("properties", {}).get("eventType", {})))
    return int(m.group(1)) if m else None


problems = []


def walk(o, n, where):
    if isinstance(o, dict) and isinstance(n, dict):
        for k in o:
            if k not in n:
                if where.endswith("properties"):
                    problems.append(f"property removed: {where}.{k}")
                continue
            walk(o[k], n[k], f"{where}.{k}")
        if "required" in n and isinstance(n["required"], list):
            added = set(n["required"]) - set(o.get("required", []))
            if added:
                problems.append(f"new required field(s) at {where}: {sorted(added)}")
        for key in ("type", "const"):
            if key in o and key in n and o[key] != n[key] and key != "const":
                problems.append(f"{key} changed at {where}: {o[key]} -> {n[key]}")
        if "enum" in o and "enum" in n:
            lost = [v for v in o["enum"] if v not in n["enum"]]
            if lost:
                problems.append(f"enum narrowed at {where}: {lost}")
    elif isinstance(o, list) and isinstance(n, list):
        for i, (a, b) in enumerate(zip(o, n)):
            walk(a, b, f"{where}[{i}]")


walk(old, new, "$")
vo, vn = version(old), version(new)
if problems and not (vo is not None and vn is not None and vn > vo):
    msg = (
        f"contract guard: {path.split('contracts/')[-1]}: breaking change without a version bump ({'; '.join(problems[:4])}). "
        "docs/data-model.md: bump the event type to .v(N+1) and add a new topic, or make the change additive. Warning only: continue if this is intended and say why."
    )
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "additionalContext": msg}}))
    print(msg, file=sys.stderr)
PY
exit 0
