SHELL := /bin/bash
PROFILE ?= core
# JDK 21 for the build: Homebrew's, else macOS java_home, else whatever the environment has (CI sets it with setup-java)
JAVA_HOME := $(or $(shell [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ] && echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home),$(shell /usr/libexec/java_home -v 21 2>/dev/null),$(JAVA_HOME))
export JAVA_HOME

# tracing is on only with the obs profile: the agents and the gateway export spans to the collector, which exists only there
OBS_ENV = $(if $(filter obs,$(subst $(comma), ,$(PROFILE))),APISIX_OTEL_SAMPLER=always_on OTEL_SDK_DISABLED=false,APISIX_OTEL_SAMPLER=always_off OTEL_SDK_DISABLED=true)
comma := ,

.DEFAULT_GOAL := help
.PHONY: help bootstrap up down logs ps verify test pki verify-mtls kafka-topics-mtls deploy-rules dc-export test-rules lint-rules test-logstash lint-api api-docs test-api-docs test-mcp lint-obs up-obs grafana-shots check-dashboards api-call cert-expiry seed-kibana export-kibana kibana-shots demo kind-up kind-deploy-rules kind-demo kind-down helm-sync lint-helm argocd-up argocd-ui argocd-status argocd-down lint-argocd ocp-render eval-rules eval-agent what-if

help: ## Show targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

bootstrap: ## Preflight: tools, Docker memory, Rosetta
	@scripts/bootstrap.sh

up: ## Start compose profiles: make up PROFILE=core,analytics (run make pki first)
	@scripts/bootstrap.sh || echo "bootstrap reported problems (continuing)"
	@$(OBS_ENV) PROFILE=$(PROFILE) scripts/compose.sh build -q # first, so the Maven builds do not compete with the starting containers
	@$(OBS_ENV) PROFILE=$(PROFILE) scripts/compose.sh up -d --remove-orphans
	@$(OBS_ENV) PROFILE=$(PROFILE) scripts/wait-healthy.sh
	@scripts/check-es-mapping.sh
	@case ',$(PROFILE),' in *,analytics,*) scripts/seed-kibana.sh ;; esac

test-mcp: ## ruff, mypy --strict and pytest of the MCP server in mcp/odm-dc-mcp-server (needs uv)
	@scripts/test-mcp.sh

lint-obs: ## Static checks of the observability config: dashboards = generator, promtool, amtool, collector validate, runbooks
	@scripts/lint-observability.sh

grafana-shots: ## Screenshots of the Grafana dashboards, the service graph and a trace waterfall (Playwright; run make demo first)
	@scripts/grafana-shots.sh

check-dashboards: ## Run every Prometheus query of the Grafana dashboards against the live Prometheus; lists those without data
	@scripts/check-dashboard-queries.py

up-obs: ## Core + gateway + observability (metrics, traces, alerts, Grafana) without Kibana: fits a 7 GB Docker VM
	@$(MAKE) --no-print-directory up PROFILE=core,edge,obs

down: ## Stop all compose profiles (make down V=1 also deletes volumes)
	@PROFILE=core,analytics,edge,obs,mcp scripts/compose.sh down $(if $(V),-v,)

logs: ## Tail logs: make logs S=kafka
	@PROFILE=core,analytics,edge,obs,mcp scripts/compose.sh logs -f --tail=100 $(S)

ps: ## Compose status
	@PROFILE=core,analytics,edge,obs,mcp scripts/compose.sh ps

verify: ## Full local verification (build + tests + format check)
	@uv run --quiet --with jsonschema python scripts/validate-contracts.py
	@if grep -q '<module>' pom.xml; then mvn -B -ntp -q verify; else echo "no Maven modules yet"; fi
	@scripts/test-logstash.sh
	@$(MAKE) --no-print-directory lint-api
	@scripts/lint-observability.sh
	@scripts/test-mcp.sh
	@scripts/lint-helm.sh

test: ## Unit tests
	@if grep -q '<module>' pom.xml; then mvn -q test; else echo "no Maven modules yet"; fi

pki: ## Generate private CA and certs (idempotent; make pki FORCE=1 CONFIRM=1 regenerates)
	@if [ -n "$(FORCE)" ] && [ "$(CONFIRM)" != "1" ]; then \
	  echo "WARNING: FORCE=1 makes a NEW CA. Kafka and Elasticsearch state signed by the old CA stops working."; \
	  echo "Re-run with CONFIRM=1 to go on, then run: make down V=1"; exit 1; fi
	@JAVA_HOME=$(JAVA_HOME) scripts/pki.sh $(if $(FORCE),--force,)
	@if [ -n "$(FORCE)" ]; then echo "New CA issued. Now run: make down V=1 (drops old volumes), then make up."; fi

seed-kibana: ## Kibana as code: space, data views, dashboards, alert rules (idempotent; make up with the analytics profile runs it)
	@scripts/seed-kibana.sh

export-kibana: ## Write the live Kibana space back to observability/kibana/ (round trip after editing in the UI)
	@scripts/export-kibana.sh

kibana-shots: ## Screenshots of the dashboards (Playwright): make kibana-shots KUSER=analyst
	@scripts/kibana-shots.sh $(or $(KUSER),analyst)

cert-expiry: ## Days left on every certificate of the demo PKI (warns below 14)
	@scripts/cert-expiry.sh

verify-mtls: ## Check TLS/mTLS endpoints and rejection of clients without certs
	@PROFILE=$(PROFILE) scripts/verify-mtls.sh

kafka-topics-mtls: ## List Kafka topics over mTLS (admin client cert)
	@PROFILE=core scripts/compose.sh exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9093 --command-config /pki/kafka-client.properties --list

deploy-rules: ## Deploy rules to RES: default via Decision Center (headless); make deploy-rules VIA=res pushes rules/ruleapps/ccApprovalApp.jar
	@$(if $(filter res,$(VIA)),scripts/deploy-ruleapp.sh,scripts/deploy-dc.sh)

dc-export: ## Export the decision service from Decision Center into rules/dc-export/
	@scripts/dc-export.sh

test-rules: ## Run the 30 scenarios in rules/tests against the deployed ruleset
	@python3 rules/tests/check_scenarios.py
	@scripts/test-ruleset.sh

test-logstash: ## Run fixture events through the real Logstash filters (PII masking, dead letters, logs); needs Docker
	@scripts/test-logstash.sh

lint-api: ## Spectral lint of docs/api/*.yaml (needs node; skipped without npx)
	@if command -v npx >/dev/null; then npx --yes @stoplight/spectral-cli@6.15.0 lint docs/api/*.yaml --fail-severity=error; else echo "SKIP  npx not found: OpenAPI lint not run"; fi

api-docs: ## Regenerate docs/api/*.yaml from the code (springdoc); mvn verify and CI fail when they drift
	@mvn -B -ntp -q -pl services/application-api,services/decision-query-api -am test -Dopenapi.write=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OpenApiContractTest
	@git diff --stat -- docs/api || true

api-call: ## curl through the gateway with a client certificate: make api-call CLIENT=analyst P=/v1/stats/latency [ARGS='-X POST ...']
	@scripts/api-call.sh $(CLIENT) $(ARGS) $(P)

test-api-docs: ## Run every curl example of docs/api/README.md against the running stack (make up PROFILE=core,analytics,edge)
	@scripts/run-api-docs.sh

lint-rules: ## Prohibited-grounds lint + scenario spec check (no ODM needed)
	@scripts/lint-prohibited-grounds.sh
	@python3 rules/tests/check_scenarios.py

demo: ## Deploy rules if missing, send 1000 synthetic applications, print topic and index counts (needs make up PROFILE=core)
	@scripts/demo.sh

kind-up: ## kind cluster + operators + images + the platform: make kind-up PROFILE=lite (7 GB VM, compose down) | full (12 GB+)
	@scripts/kind.sh up $(if $(filter lite full,$(PROFILE)),$(PROFILE),lite)

kind-deploy-rules: ## Port-forward ODM in kind and deploy the rules (scripts/deploy-dc.sh)
	@scripts/kind.sh deploy-rules

kind-demo: ## 200 synthetic applications through the gateway in kind; record counts per Kafka topic from the broker pod
	@scripts/kind.sh demo $(or $(TOTAL),200)

kind-down: ## Delete the kind cluster
	@scripts/kind.sh down

helm-sync: ## Copy the compose configuration into the charts and regenerate Kafka topics/users (never edit the copies)
	@scripts/helm-sync.sh

lint-helm: ## helm lint + template (kind-lite, kind-full, openshift) + kubeconform + kube-linter (needs kubeconform, kube-linter)
	@scripts/lint-helm.sh

argocd-up: ## Argo CD on kind with everything synced from Git: make argocd-up ENV=kind-lite|kind-full [REVISION=branch] [IMAGES=local]
	@scripts/argocd.sh up $(or $(ENV),kind-lite)

argocd-ui: ## Port-forward the Argo CD UI to https://localhost:8080 and print the admin login
	@scripts/argocd.sh ui

argocd-status: ## Applications with sync and health
	@scripts/argocd.sh status

argocd-down: ## Delete the kind cluster (and with it Argo CD and everything it deployed)
	@scripts/argocd.sh down

lint-argocd: ## Argo CD manifests: kubeconform, policy checks per environment, every Application rendered
	@scripts/lint-argocd.sh

ocp-render: ## helm template for OpenShift values -> out/openshift/*.yaml (add CA=path to embed the CA for re-encrypt Routes)
	@scripts/ocp-render.sh

eval-rules: ## Golden-dataset rule regression
	@echo "TODO(P14): eval-rules"; exit 0

eval-agent: ## promptfoo agent/MCP evals (needs ANTHROPIC_API_KEY)
	@echo "TODO(P14): eval-agent"; exit 0

what-if: ## Champion/challenger replay: make what-if CHALLENGER=1.1
	@echo "TODO(P14): what-if"; exit 0
