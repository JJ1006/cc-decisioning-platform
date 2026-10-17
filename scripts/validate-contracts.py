#!/usr/bin/env python3
"""Validate contracts/examples/<name>.example.json against contracts/<name>.schema.json (JSON Schema 2020-12)."""

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

root = Path(__file__).resolve().parent.parent / "contracts"
schemas = {p.name: json.loads(p.read_text()) for p in root.glob("*.schema.json")}
registry = Registry().with_resources(
    (s["$id"], Resource.from_contents(s)) for s in schemas.values()
)

failed = 0
for name, schema in sorted(schemas.items()):
    Draft202012Validator.check_schema(schema)
    example = root / "examples" / name.replace(".schema.json", ".example.json")
    if name in ("envelope.schema.json", "defs.schema.json"):
        continue
    if not example.exists():
        print(f"FAIL {name}: missing example")
        failed += 1
        continue
    errors = list(
        Draft202012Validator(schema, registry=registry).iter_errors(
            json.loads(example.read_text())
        )
    )
    for e in errors:
        print(f"FAIL {example.name}: {'/'.join(map(str, e.path))}: {e.message}")
    failed += bool(errors)
    print(f"{'FAIL' if errors else 'ok  '} {example.name}")
sys.exit(1 if failed else 0)
