---
name: kibana-dashboard
description: "Add or change a Kibana dashboard, Lens panel, saved search or alert rule of the Maple Northern space. Use when asked to edit Kibana, a dashboard panel, a Lens visualization, a fairness or operations panel, or to refresh the Kibana screenshots."
---

# Kibana dashboard

Kibana is code: `observability/kibana/` (space, `saved-objects.ndjson`, `alerting/`). The ndjson is the source of truth once Kibana owns it; `bootstrap/gen_saved_objects.py` is the generator used to add many panels at once.

## Steps

1. Stack up: `make up PROFILE=core,analytics,edge`, data: `make demo`.
2. Edit either in the Kibana UI (space `maple-northern`, <https://localhost:5601>, user `analyst` or `ops`) **or** in `gen_saved_objects.py` then `python3 observability/kibana/bootstrap/gen_saved_objects.py && make seed-kibana`.
3. **Analysts read `cc-decisions` only: bands, never exact score, TDS or income** (`scoreBand`, `tdsBand`, `incomeBand`, `limitBand`, `ageBand`). Do not add a panel on `cc-decision-inputs` (restricted).
4. `make export-kibana` writes the live space back (sorted, no timestamps). If rule files only changed their name, restore them with `git checkout observability/kibana/alerting/rules`.
5. `make kibana-shots KUSER=analyst` refreshes `docs/images/kibana-*.png`; open them and check every panel has data (the script prints `PROBLEM` for empty or broken panels).
6. Docs: `docs/kibana.md`.

## Verify

```bash
make seed-kibana && make kibana-shots KUSER=analyst && git diff --stat observability docs/images
```
