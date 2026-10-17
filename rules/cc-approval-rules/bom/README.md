# BOM

The BOM is generated from the XOM, not hand-written, so it always matches the Java classes:

1. New > BOM Entry > Java Execution Object Model, add the project/jar `rules/xom` (built by `mvn -pl rules/xom package`).
2. Select all classes under `ca.maplenorthern.cc.xom` and finish.
3. In the BOM Editor choose Verbalization > Import, or overwrite `model_en_US.voc` with the file in this folder.

Do not add members for prohibited grounds; `make lint-rules` fails the build if any rules or vocabulary mention them.

Tip: untick `dateOfBirth` and `sin` for the BOM (rules do not use them; the orchestrator derives `ageYears`, and a LocalDate does not travel well as JSON through RES).
