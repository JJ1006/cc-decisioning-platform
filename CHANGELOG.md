# Changelog

## [1.0.0](https://github.com/JJ1006/cc-decisioning-platform/compare/v0.1.3...v1.0.0) (2026-10-22)


### Features

* **evals:** golden dataset, regression gate, what-if, agent evals, load and chaos (P14) ([#56](https://github.com/JJ1006/cc-decisioning-platform/issues/56)) ([74900c2](https://github.com/JJ1006/cc-decisioning-platform/commit/74900c2e54b33252465073cc3b0cc79a122dfdcb))


### Bug Fixes

* **odm:** cap the JVM heap at about 65% of the container limit ([#54](https://github.com/JJ1006/cc-decisioning-platform/issues/54)) ([c1112b7](https://github.com/JJ1006/cc-decisioning-platform/commit/c1112b74b35ff5dd9a611fbaa5b7179a3a0ee4be))


### Miscellaneous Chores

* phase 15 hardening and 1.0 release prep ([#57](https://github.com/JJ1006/cc-decisioning-platform/issues/57)) ([78a3467](https://github.com/JJ1006/cc-decisioning-platform/commit/78a346712300cbec43b2b580cccbae9d517b70a8))

## [0.1.3](https://github.com/JJ1006/cc-decisioning-platform/compare/v0.1.2...v0.1.3) (2026-10-22)


### Features

* **argocd:** GitOps with Argo CD for kind and OpenShift (P11) ([#42](https://github.com/JJ1006/cc-decisioning-platform/issues/42)) ([556afe6](https://github.com/JJ1006/cc-decisioning-platform/commit/556afe6ceafd2855499ab16364d178b09c357737))
* **claude:** AI tooling: vetted MCP servers, skills, agents, commands, hooks (P13) ([#53](https://github.com/JJ1006/cc-decisioning-platform/issues/53)) ([d74d474](https://github.com/JJ1006/cc-decisioning-platform/commit/d74d47480bfb52a0282e088683a7b3fc373da9f9))
* **privacy:** guardrails, explainability and privacy requests (P12) ([#49](https://github.com/JJ1006/cc-decisioning-platform/issues/49)) ([0536bd9](https://github.com/JJ1006/cc-decisioning-platform/commit/0536bd9bdb255a0cca466f5426db444503bd9ea0))


### Bug Fixes

* **ci:** approve the held workflow runs of bot pull requests ([#47](https://github.com/JJ1006/cc-decisioning-platform/issues/47)) ([5eb206a](https://github.com/JJ1006/cc-decisioning-platform/commit/5eb206a1e60a94bfa450c78709b32e38f78edb40))
* **ci:** run the required ci check on the branches of bot pull requests ([#45](https://github.com/JJ1006/cc-decisioning-platform/issues/45)) ([3dec1ec](https://github.com/JJ1006/cc-decisioning-platform/commit/3dec1ec12dc12c97c217c5e0878aafcd7f2bff92))
* **privacy:** P12 follow-ups: bands only in cc-decisions, Kafka ACLs, rules restore, Kibana panels ([#51](https://github.com/JJ1006/cc-decisioning-platform/issues/51)) ([2be7392](https://github.com/JJ1006/cc-decisioning-platform/commit/2be7392b7f3634b599a932daa8d8fbbcea2f57fd))

## [0.1.2](https://github.com/JJ1006/cc-decisioning-platform/compare/v0.1.1...v0.1.2) (2026-10-18)


### Features

* **kibana:** dashboards, alert rules and roles as code (P7) ([#35](https://github.com/JJ1006/cc-decisioning-platform/issues/35)) ([8f641cd](https://github.com/JJ1006/cc-decisioning-platform/commit/8f641cd0c2099456c97490dfb3c8513524794b71))
* **mcp:** official ODM MCP server and our odm-dc MCP server, read-only and audited (P9) ([#38](https://github.com/JJ1006/cc-decisioning-platform/issues/38)) ([05c1755](https://github.com/JJ1006/cc-decisioning-platform/commit/05c175594f1963ca1e5c7d6d763517cd38d84072))
* **obs:** OpenTelemetry traces, Prometheus, Tempo, Grafana and alert runbooks (P8) ([#37](https://github.com/JJ1006/cc-decisioning-platform/issues/37)) ([cfda69f](https://github.com/JJ1006/cc-decisioning-platform/commit/cfda69fac89964b820134b606254f4471d439a6d))

## [0.1.1](https://github.com/JJ1006/cc-decisioning-platform/compare/v0.1.0...v0.1.1) (2026-10-17)


### Features

* **api:** application-api and decision-query-api with OpenAPI and Spectral (P5) ([#32](https://github.com/JJ1006/cc-decisioning-platform/issues/32)) ([fd3122c](https://github.com/JJ1006/cc-decisioning-platform/commit/fd3122c86f020c4fd205496b20c73aa7decdf8c0))
* **elk:** Logstash and Filebeat replace es-indexer, Loki dropped (P4b) ([#30](https://github.com/JJ1006/cc-decisioning-platform/issues/30)) ([663bdd1](https://github.com/JJ1006/cc-decisioning-platform/commit/663bdd17a88c64036adc988e015549743237dd92))
* **services:** event services, ES indexing with PII masking and make demo (P4) ([#27](https://github.com/JJ1006/cc-decisioning-platform/issues/27)) ([4007e06](https://github.com/JJ1006/cc-decisioning-platform/commit/4007e064529dcf20d98a44defc2481796b992fb7))

## 0.1.0 (2026-10-17)


### Features

* contracts, XOM and common-lib (P1) ([#9](https://github.com/JJ1006/cc-decisioning-platform/issues/9)) ([596344e](https://github.com/JJ1006/cc-decisioning-platform/commit/596344ed474c184f60199b85068464a41457b96b))
* private PKI and local Compose infrastructure (P2) ([#17](https://github.com/JJ1006/cc-decisioning-platform/issues/17)) ([b4bde42](https://github.com/JJ1006/cc-decisioning-platform/commit/b4bde42439f401f9768fce2d5dc9490f91211cab))
* **rules:** ODM ccApproval rules via headless Decision Center flow (P3) ([#25](https://github.com/JJ1006/cc-decisioning-platform/issues/25)) ([dd5d7d8](https://github.com/JJ1006/cc-decisioning-platform/commit/dd5d7d87fb0238f6af41249f51b3cfad33a652c8))


### Bug Fixes

* **ci:** pin trivy-action to existing tag, trim dependabot ecosystems ([#8](https://github.com/JJ1006/cc-decisioning-platform/issues/8)) ([ffa11cd](https://github.com/JJ1006/cc-decisioning-platform/commit/ffa11cdc483fea03c96217367389e350bd8521c0))
* **deps:** patch jackson-databind and lz4-java HIGH CVEs ([#10](https://github.com/JJ1006/cc-decisioning-platform/issues/10)) ([9c29bba](https://github.com/JJ1006/cc-decisioning-platform/commit/9c29bba9b012716ad6f7b67f4f48a369105cb491))

## Changelog

All notable changes are generated by release-please from Conventional Commits.
