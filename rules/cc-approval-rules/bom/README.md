# BOM

There is no hand-written BOM. `scripts/gen_dc_project.py` generates `model.bom` and `model_en.voc` from the Java classes in
`rules/xom` (all beans and enums, except `Applicant.dateOfBirth` and `Applicant.sin`, which no rule uses), so the BOM cannot
drift from the XOM. Wording of the vocabulary lives in the `LABELS` / `ATTR_LABELS` / `METHODS` tables of the generator, and
the rules in `../rules/` are written against it. The generated result is visible in `rules/dc-export/cc-approval/bom/`.

Do not add members for prohibited grounds; `make lint-rules` fails the build if any rule, vocabulary or the catalog mentions them.
