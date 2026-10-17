SHELL := /bin/bash
PROFILE ?= core
JAVA_HOME := $(shell [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ] && echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home || /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME

.DEFAULT_GOAL := help
.PHONY: help up down logs verify test pki demo kind-up ocp-render eval-rules eval-agent what-if

help: ## Show targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

up: ## Start compose profiles: make up PROFILE=core,analytics
	@echo "TODO(P2): docker compose --profile $(PROFILE) up -d"; exit 0

down: ## Stop compose stack
	@echo "TODO(P2): docker compose down"; exit 0

logs: ## Tail logs
	@echo "TODO(P2): docker compose logs -f"; exit 0

verify: ## Full local verification (build + tests + format check)
	@uv run --quiet --with jsonschema python scripts/validate-contracts.py
	@if grep -q '<module>' pom.xml; then mvn -B -ntp -q verify; else echo "no Maven modules yet"; fi

test: ## Unit tests
	@if grep -q '<module>' pom.xml; then mvn -q test; else echo "no Maven modules yet"; fi

pki: ## Generate private CA and certs
	@echo "TODO(P2): scripts/pki.sh"; exit 0

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
