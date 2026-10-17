SHELL := /bin/bash
PROFILE ?= core
JAVA_HOME := $(shell [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ] && echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home || /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME

.DEFAULT_GOAL := help
.PHONY: help bootstrap up down logs ps verify test pki verify-mtls kafka-topics-mtls deploy-rules dc-export test-rules lint-rules demo kind-up ocp-render eval-rules eval-agent what-if

help: ## Show targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

bootstrap: ## Preflight: tools, Docker memory, Rosetta
	@scripts/bootstrap.sh

up: ## Start compose profiles: make up PROFILE=core,analytics (run make pki first)
	@scripts/bootstrap.sh || echo "bootstrap reported problems (continuing)"
	@PROFILE=$(PROFILE) scripts/compose.sh up -d
	@PROFILE=$(PROFILE) scripts/wait-healthy.sh

down: ## Stop all compose profiles (make down V=1 also deletes volumes)
	@PROFILE=core,analytics,edge,obs,mcp scripts/compose.sh down $(if $(V),-v,)

logs: ## Tail logs: make logs S=kafka
	@PROFILE=core,analytics,edge,obs,mcp scripts/compose.sh logs -f --tail=100 $(S)

ps: ## Compose status
	@PROFILE=core,analytics,edge,obs,mcp scripts/compose.sh ps

verify: ## Full local verification (build + tests + format check)
	@uv run --quiet --with jsonschema python scripts/validate-contracts.py
	@if grep -q '<module>' pom.xml; then mvn -B -ntp -q verify; else echo "no Maven modules yet"; fi

test: ## Unit tests
	@if grep -q '<module>' pom.xml; then mvn -q test; else echo "no Maven modules yet"; fi

pki: ## Generate private CA and certs (idempotent; make pki FORCE=1 regenerates)
	@JAVA_HOME=$(JAVA_HOME) scripts/pki.sh $(if $(FORCE),--force,)

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

lint-rules: ## Prohibited-grounds lint + scenario spec check (no ODM needed)
	@scripts/lint-prohibited-grounds.sh
	@python3 rules/tests/check_scenarios.py

demo: ## Send 1000 synthetic applications
	@echo "TODO(P4): txn-generator demo"; exit 0

kind-up: ## Create kind cluster + install platform
	@echo "TODO(P10): kind-up"; exit 0

ocp-render: ## helm template for OpenShift values
	@echo "TODO(P10): helm template openshift"; exit 0

eval-rules: ## Golden-dataset rule regression
	@echo "TODO(P14): eval-rules"; exit 0

eval-agent: ## promptfoo agent/MCP evals (needs ANTHROPIC_API_KEY)
	@echo "TODO(P14): eval-agent"; exit 0

what-if: ## Champion/challenger replay: make what-if CHALLENGER=1.1
	@echo "TODO(P14): what-if"; exit 0
