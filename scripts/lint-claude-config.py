#!/usr/bin/env python3
"""Lints the Claude Code project setup: .claude/skills, agents, commands, hooks, settings.json and .mcp.json.

  uv run --quiet --with pyyaml==6.0.3 python scripts/lint-claude-config.py     (make lint-claude, part of make verify)

Checks: every SKILL.md / agent / command has valid YAML frontmatter with the required keys, names are unique per kind, a skill's name equals its
folder, a skill or agent description has trigger words (is long enough), agents declare read-only tools (no Edit, Write, NotebookEdit; Bash only with
a pattern), every hook script in settings.json exists and is executable, .mcp.json has no @latest / floating tag and no literal secret.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CLAUDE = ROOT / ".claude"
errors: list[str] = []
listing: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def frontmatter(path: Path) -> dict | None:
    text = path.read_text()
    m = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    if not m:
        err(f"{path.relative_to(ROOT)}: no YAML frontmatter")
        return None
    try:
        data = yaml.safe_load(m.group(1))
    except yaml.YAMLError as e:
        err(f"{path.relative_to(ROOT)}: frontmatter is not valid YAML: {e}")
        return None
    if not isinstance(data, dict):
        err(f"{path.relative_to(ROOT)}: frontmatter is not a mapping")
        return None
    return data


# skills
names: dict[str, Path] = {}
for p in sorted((CLAUDE / "skills").glob("*/SKILL.md")):
    fm = frontmatter(p)
    if fm is None:
        continue
    name, desc = fm.get("name"), fm.get("description", "")
    if not name or not desc:
        err(f"{p.relative_to(ROOT)}: name and description are required")
        continue
    if name != p.parent.name:
        err(
            f"{p.relative_to(ROOT)}: name '{name}' differs from the folder '{p.parent.name}'"
        )
    if name in names:
        err(f"skill name '{name}' is not unique")
    names[name] = p
    if not re.search(r"\bUse when\b", desc):
        err(
            f"{p.relative_to(ROOT)}: description should say when to use it (trigger words: 'Use when ...')"
        )
    body = p.read_text().split("---\n", 2)[-1]
    for h in ("## Steps", "## Verify"):
        if h not in body:
            err(f"{p.relative_to(ROOT)}: missing section '{h}'")
    listing.append(f"skill   {name}")

# agents
names = {}
for p in sorted((CLAUDE / "agents").glob("*.md")):
    fm = frontmatter(p)
    if fm is None:
        continue
    name, desc, tools = (
        fm.get("name"),
        fm.get("description", ""),
        str(fm.get("tools", "")),
    )
    if not name or not desc or not tools:
        err(f"{p.relative_to(ROOT)}: name, description and tools are required")
        continue
    if name != p.stem:
        err(f"{p.relative_to(ROOT)}: name '{name}' differs from the file name")
    if name in names:
        err(f"agent name '{name}' is not unique")
    names[name] = p
    for t in [x.strip() for x in re.split(r",\s*(?![^()]*\))", tools)]:
        base = t.split("(")[0]
        if base in {"Edit", "Write", "NotebookEdit", "MultiEdit"}:
            err(f"{p.relative_to(ROOT)}: agent is read only, tool '{t}' is not allowed")
        if base == "Bash" and "(" not in t:
            err(
                f"{p.relative_to(ROOT)}: Bash must be limited with a pattern, for example Bash(git diff:*)"
            )
    listing.append(f"agent   {name}")

# commands
names = {}
for p in sorted((CLAUDE / "commands").glob("*.md")):
    fm = frontmatter(p)
    if fm is None:
        continue
    if not fm.get("description"):
        err(f"{p.relative_to(ROOT)}: description is required")
    if p.stem in names:
        err(f"command '{p.stem}' is not unique")
    names[p.stem] = p
    listing.append(f"command /{p.stem}")

# settings.json: hooks exist
settings = json.loads((CLAUDE / "settings.json").read_text())
for event, groups in settings.get("hooks", {}).items():
    for g in groups:
        for h in g.get("hooks", []):
            m = re.search(r"\.claude/hooks/[\w.-]+\.sh", h.get("command", ""))
            if not m:
                continue
            script = ROOT / m.group(0)
            if not script.is_file():
                err(f"settings.json {event}: {m.group(0)} does not exist")
            elif not script.stat().st_mode & 0o111:
                err(f"{m.group(0)} is not executable")

# .mcp.json: pinned, no literal secrets
mcp = json.loads((ROOT / ".mcp.json").read_text())
for name, s in mcp.get("mcpServers", {}).items():
    line = " ".join([s.get("command", ""), *s.get("args", [])])
    if re.search(r"@latest\b", line):
        err(f".mcp.json {name}: @latest is not pinned")
    if s.get("command") == "docker":
        images = [
            a
            for a in s["args"]
            if re.match(r"^[\w.-]+(\.[\w.-]+)?/[\w./-]+(:[\w.-]+)?$", a)
            and "/" in a
            and not a.startswith("-")
        ]
        for img in images:
            if ":" not in img or img.endswith(":latest"):
                err(f".mcp.json {name}: image {img} has no pinned tag")
    for k, v in s.get("env", {}).items():
        if v and not re.fullmatch(r"\$\{[A-Z0-9_]+(:-[^}]*)?\}", str(v)):
            err(
                f".mcp.json {name}: env {k} must be a ${{ENV}} expansion, not a literal value"
            )
    listing.append(f"mcp     {name}")

if "-v" in sys.argv or "--list" in sys.argv:
    print("\n".join(listing))
if errors:
    print("\n".join(f"FAIL  {e}" for e in errors), file=sys.stderr)
    sys.exit(1)
print(
    f"claude config ok: {sum(1 for l in listing if l.startswith('skill'))} skills, {sum(1 for l in listing if l.startswith('agent'))} agents, "
    f"{sum(1 for l in listing if l.startswith('command'))} commands, {sum(1 for l in listing if l.startswith('mcp'))} MCP servers"
)
