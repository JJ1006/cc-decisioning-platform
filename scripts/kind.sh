#!/usr/bin/env bash
# The platform on a local kind cluster (docs/deploy-kubernetes.md). Make targets call this:
#   scripts/kind.sh up [lite|full]     cluster + operators + secrets + images + cc-infra + cc-platform   (make kind-up PROFILE=lite)
#   scripts/kind.sh deploy-rules       port-forward ODM and run scripts/deploy-dc.sh                      (make kind-deploy-rules)
#   scripts/kind.sh demo [total]       generator through the gateway, counts per Kafka topic via the pod   (make kind-demo)
#   scripts/kind.sh status             pods of every namespace
#   scripts/kind.sh cluster [lite|full]  only the cluster (IMAGES=local also builds and loads the images): for Argo CD, scripts/argocd.sh
#   scripts/kind.sh down               delete the cluster                                                  (make kind-down)
# lite = Kafka, ODM, orchestrator, generator, notification, application-api, gateway (fits a 7 GB Docker VM, compose must be down)
# full = lite + Elasticsearch/Kibana (ECK), Logstash, Filebeat, query API, MCP server, observability, ingress-nginx (12 GB+)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
# shellcheck source=../deploy/kind/versions.env
source deploy/kind/versions.env
CLUSTER=cc
NS=cc
PROFILE_NAME=${2:-${PROFILE:-lite}}
H=deploy/helm
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
export JAVA_HOME
IMAGE_REGISTRY=${IMAGE_REGISTRY:-ghcr.io/jj1006}
IMAGE_TAG=${IMAGE_TAG:-dev}

need() { command -v "$1" >/dev/null || { echo "NEED USER: install $1 (brew install $1)"; exit 2; }; }
say() { printf '\n==> %s\n' "$*"; }
ctx() { kubectl --context "kind-$CLUSTER" "$@"; }
hlm() { helm --kube-context "kind-$CLUSTER" "$@"; }

compose_must_be_down() {
  local running
  running=$(docker ps --filter "name=ccplatform-" --format '{{.Names}}' | wc -l | tr -d ' ')
  if [[ "$running" != 0 ]]; then
    echo "NEED USER: the compose stack is running ($running containers) and the kind cluster needs the same RAM: run  make down V=1"
    exit 2
  fi
}

images_for() { # the images each profile builds from this repo
  echo txn-generator decision-orchestrator notification-svc application-api
  [[ $1 == full ]] && echo decision-query-api
  return 0
}

build_images() {
  local profile=$1 svc
  for svc in $(images_for "$profile"); do
    if [[ -n "$(docker image ls -q "$IMAGE_REGISTRY/cc-$svc:$IMAGE_TAG")" && -z "${REBUILD:-}" ]]; then
      echo "image $IMAGE_REGISTRY/cc-$svc:$IMAGE_TAG exists (REBUILD=1 to rebuild)"
    else
      say "building $svc"
      docker build -q -f "services/$svc/Dockerfile" -t "$IMAGE_REGISTRY/cc-$svc:$IMAGE_TAG" .
    fi
  done
  if [[ $profile == full ]]; then
    if [[ -z "$(docker image ls -q "$IMAGE_REGISTRY/cc-odm-dc-mcp:$IMAGE_TAG")" || -n "${REBUILD:-}" ]]; then
      say "building odm-dc-mcp"
      docker build -q -f mcp/odm-dc-mcp-server/Dockerfile -t "$IMAGE_REGISTRY/cc-odm-dc-mcp:$IMAGE_TAG" .
    fi
  fi
}

load_images() { # kind load for what this repo builds, and for the third-party images that are already on this machine (saves the pull)
  local profile=$1 svc img
  local list=()
  for svc in $(images_for "$profile"); do list+=("$IMAGE_REGISTRY/cc-$svc:$IMAGE_TAG"); done
  [[ $profile == full ]] && list+=("$IMAGE_REGISTRY/cc-odm-dc-mcp:$IMAGE_TAG")
  for img in "${list[@]}" icr.io/cpopen/odm-k8s/odm:9.5.0.1-IF019_25.1.0 apache/apisix:3.13.0-debian; do
    if [[ -n "$(docker image ls -q "$img")" ]]; then
      echo "kind load $img"
      kind load docker-image --name "$CLUSTER" "$img" >/dev/null 2>&1 || echo "  (could not load $img, the node will pull it)"
    else
      echo "  $img not on this machine: the node pulls it"
    fi
  done
}

install_operators() {
  local profile=$1
  hlm repo add jetstack https://charts.jetstack.io >/dev/null 2>&1 || true
  hlm repo add strimzi https://strimzi.io/charts/ >/dev/null 2>&1 || true
  hlm repo update >/dev/null
  say "cert-manager $CERT_MANAGER_CHART"
  hlm upgrade --install cert-manager jetstack/cert-manager --version "$CERT_MANAGER_CHART" -n cert-manager --create-namespace \
    --set crds.enabled=true --set resources.requests.memory=64Mi --set cainjector.resources.requests.memory=64Mi \
    --set webhook.resources.requests.memory=32Mi --wait --timeout 5m >/dev/null
  ctx create namespace "$NS" --dry-run=client -o yaml | ctx apply -f - >/dev/null # the operators watch it
  say "Strimzi $STRIMZI_CHART"
  hlm upgrade --install strimzi strimzi/strimzi-kafka-operator --version "$STRIMZI_CHART" -n strimzi-system --create-namespace \
    --set "watchNamespaces={$NS}" --set resources.requests.memory=256Mi --set resources.limits.memory=384Mi --wait --timeout 5m >/dev/null
  if [[ $profile == full ]]; then
    hlm repo add elastic https://helm.elastic.co >/dev/null 2>&1 || true
    hlm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx >/dev/null 2>&1 || true
    hlm repo update >/dev/null
    say "ECK $ECK_CHART"
    hlm upgrade --install eck elastic/eck-operator --version "$ECK_CHART" -n elastic-system --create-namespace \
      --set "managedNamespaces={$NS}" --set resources.requests.memory=128Mi --wait --timeout 5m >/dev/null
    say "ingress-nginx $INGRESS_NGINX_CHART"
    hlm upgrade --install ingress-nginx ingress-nginx/ingress-nginx --version "$INGRESS_NGINX_CHART" -n ingress-nginx --create-namespace \
      --set controller.hostPort.enabled=true --set controller.service.type=ClusterIP --set controller.nodeSelector.ingress-ready=true \
      --set "controller.tolerations[0].key=node-role.kubernetes.io/control-plane" --set "controller.tolerations[0].operator=Exists" \
      --set "controller.tolerations[0].effect=NoSchedule" --set controller.extraArgs.enable-ssl-passthrough=true \
      --set controller.admissionWebhooks.enabled=false --wait --timeout 5m >/dev/null
  fi
}

cluster() { # just the cluster (and, with IMAGES=local, the locally built images): Argo CD installs everything else (scripts/argocd.sh)
  local profile=$1
  need docker; need kind; need kubectl; need helm
  docker info >/dev/null 2>&1 || { echo "NEED USER: start Docker Desktop"; exit 2; }
  compose_must_be_down
  scripts/pki.sh >/dev/null
  if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    say "kind cluster $CLUSTER"
    kind create cluster --config deploy/kind/cluster.yaml --image "$KIND_NODE_IMAGE" --wait 120s
  else
    echo "kind cluster $CLUSTER exists"
  fi
  if [[ ${IMAGES:-ghcr} == local ]]; then build_images "$profile"; load_images "$profile"; fi
}

up() {
  local profile=$1
  [[ $profile == lite || $profile == full ]] || { echo "PROFILE must be lite or full" >&2; exit 2; }
  need docker; need kind; need kubectl; need helm
  docker info >/dev/null 2>&1 || { echo "NEED USER: start Docker Desktop"; exit 2; }
  compose_must_be_down
  say "platform CA (make pki)"
  scripts/pki.sh >/dev/null
  if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    say "kind cluster $CLUSTER"
    kind create cluster --config deploy/kind/cluster.yaml --image "$KIND_NODE_IMAGE" --wait 120s
  else
    echo "kind cluster $CLUSTER exists"
  fi
  build_images "$profile"
  load_images "$profile"
  install_operators "$profile"
  say "namespace $NS and secrets"
  scripts/k8s-secrets.sh "kind-$CLUSTER" "$NS" "$profile"
  hlm repo add strimzi https://strimzi.io/charts/ >/dev/null 2>&1 || true
  scripts/helm-deps.sh >/dev/null
  say "cc-infra ($profile)"
  hlm upgrade --install infra "$H/cc-infra" -n "$NS" -f "$H/cc-infra/values-kind-$profile.yaml" --wait --timeout 10m >/dev/null
  say "waiting for Kafka (Strimzi)"
  ctx -n "$NS" wait kafka/cc --for=condition=Ready --timeout=600s
  ctx -n "$NS" wait kafkauser --all --for=condition=Ready --timeout=300s
  ctx -n "$NS" wait kafkatopic --all --for=condition=Ready --timeout=300s
  if [[ $profile == full ]]; then
    say "waiting for Elasticsearch and Kibana (ECK)"
    ctx -n "$NS" wait elasticsearch/cc --for=jsonpath='{.status.health}'=green --timeout=900s || ctx -n "$NS" wait elasticsearch/cc --for=jsonpath='{.status.phase}'=Ready --timeout=300s
  fi
  say "cc-platform ($profile)"
  hlm upgrade --install cc "$H/cc-platform" -n "$NS" -f "$H/cc-platform/values-kind-$profile.yaml" \
    --set "global.image.registry=${IMAGE_REGISTRY%%/*}" --set "global.image.tag=$IMAGE_TAG" >/dev/null
  if [[ $profile == full ]]; then # separate releases: cluster-scoped objects (Filebeat's ClusterRole, the Prometheus stack) stay out of the namespaced umbrella
    say "filebeat and cc-observability"
    hlm upgrade --install filebeat "$H/charts/filebeat" -n "$NS" --set "image.tag=8.19.23" >/dev/null
    hlm upgrade --install obs "$H/charts/cc-observability" -n "$NS" -f "$H/charts/cc-observability/values-kind-full.yaml" --timeout 10m >/dev/null
  fi
  local d
  for d in $(ctx -n "$NS" get deploy -o name); do
    ctx -n "$NS" rollout status "$d" --timeout=600s
  done
  say "kind cluster ready"
  ctx get pods -A
  echo
  echo "gateway: https://localhost:8443 (mutual TLS, certificates from make pki)   next: make kind-deploy-rules && make kind-demo"
}

port_forward_odm() { # background port-forward to ODM on :9443, same as compose
  ctx -n "$NS" port-forward svc/odm 9443:9453 >/dev/null 2>&1 &
  PF=$!
  trap 'kill $PF 2>/dev/null || true' EXIT
  for _ in $(seq 1 30); do
    (exec 3<>/dev/tcp/127.0.0.1/9443) 2>/dev/null && return 0
    sleep 1
  done
  echo "port-forward to ODM failed" >&2
  exit 1
}

deploy_rules() {
  need kubectl; need jq
  ctx -n "$NS" rollout status deploy/odm --timeout=600s
  port_forward_odm
  RES_URL=https://localhost:9443 scripts/deploy-dc.sh
  # a freshly deployed ruleset answers 404 for a moment: wait until RES executes it, or the first applications of a demo end up in the dead-letter topic
  # shellcheck source=res-env.sh
  (
    export RES_URL=https://localhost:9443
    source scripts/res-env.sh
    for _ in $(seq 1 60); do
      code=$(res_curl -o /dev/null -w '%{http_code}' -X POST "$RES_URL/DecisionService/rest/$RULEAPP/$RULEAPP_VERSION/$RULESET/$RULESET_VERSION" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{}' 2>/dev/null || echo 000)
      [[ "$code" != 404 && "$code" != 000 ]] && exit 0
      sleep 2
    done
    echo "WARN  the ruleset still answers 404 or nothing" >&2
  )
}

kafka_pod() { ctx -n "$NS" get pod -l strimzi.io/cluster=cc,strimzi.io/kind=Kafka,strimzi.io/broker-role=true -o jsonpath='{.items[0].metadata.name}'; }

kafka_client_setup() { # the admin KafkaUser's store and the cluster CA store inside the broker pod, for the Kafka CLI tools
  local pod=$1 up cp
  up=$(ctx -n "$NS" get secret admin -o jsonpath='{.data.user\.password}' | base64 -d)
  cp=$(ctx -n "$NS" get secret cc-cluster-ca-cert -o jsonpath='{.data.ca\.password}' | base64 -d)
  ctx -n "$NS" get secret admin -o jsonpath='{.data.user\.p12}' | base64 -d | ctx -n "$NS" exec -i "$pod" -c kafka -- sh -c 'cat > /tmp/admin.p12'
  ctx -n "$NS" get secret cc-cluster-ca-cert -o jsonpath='{.data.ca\.p12}' | base64 -d | ctx -n "$NS" exec -i "$pod" -c kafka -- sh -c 'cat > /tmp/ca.p12'
  printf 'security.protocol=SSL\nssl.keystore.type=PKCS12\nssl.keystore.location=/tmp/admin.p12\nssl.keystore.password=%s\nssl.truststore.type=PKCS12\nssl.truststore.location=/tmp/ca.p12\nssl.truststore.password=%s\n' "$up" "$cp" |
    ctx -n "$NS" exec -i "$pod" -c kafka -- sh -c 'cat > /tmp/admin.properties'
}

topic_count() { # topic_count POD TOPIC: data records (read_committed), not offsets: transactional topics hold commit markers
  ctx -n "$NS" exec "$1" -c kafka -- env KAFKA_HEAP_OPTS=-Xmx128m /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server cc-kafka-bootstrap:9093 \
    --consumer.config /tmp/admin.properties --topic "$2" --from-beginning --isolation-level read_committed --timeout-ms 20000 2>/dev/null | wc -l | tr -d ' '
}

decisions_made() { # the orchestrator's own counter of decisions (a Kafka console consumer miscounts under load): one number, monotonic per pod
  ctx -n "$NS" exec deploy/decision-orchestrator -- curl -s localhost:8080/actuator/prometheus | awk '/^decision_latency_seconds_count/{print int($2)}'
}

demo() {
  local total=${1:-200}
  need kubectl; need jq; need curl
  local gen="scripts/api-call.sh ops-admin -f" base now prev=-1 delta=0
  base=$(decisions_made)
  say "generator through the gateway: total=$total (orchestrator has made $base decisions so far)"
  # shellcheck disable=SC2086
  $gen -X POST "/admin/generator/start?total=$total&tps=50&poisonRate=0" | jq -c .
  while [[ $($gen /admin/generator/status | jq -r .running) == true ]]; do sleep 2; done
  $gen /admin/generator/status | jq -c .
  for _ in $(seq 1 60); do # decisions come after the ODM call: wait until all arrived or the counter stops
    now=$(decisions_made)
    delta=$((now - base))
    [[ $delta -ge $total || $now == "$prev" ]] && break
    prev=$now
    sleep 5
  done
  local pod
  pod=$(kafka_pod)
  kafka_client_setup "$pod"
  say "records per Kafka topic, all runs so far (read_committed, via $pod)"
  local t
  for t in cc.applications.submitted.v1 cc.decisions.approved.v1 cc.decisions.declined.v1 cc.decisions.referred.v1 cc.notifications.v1 cc.applications.submitted.v1.dlq; do
    printf '%-34s %s\n' "$t" "$(topic_count "$pod" "$t")"
  done
  if [[ $delta -eq $total ]]; then
    echo "PASS  this run: $delta decisions for $total applications"
  else
    echo "FAIL  this run: $delta decisions for $total applications: are the rules deployed (make kind-deploy-rules)? orchestrator logs: kubectl --context kind-$CLUSTER -n $NS logs deploy/decision-orchestrator" >&2
    exit 1
  fi
}

case "${1:-}" in
  up) up "$PROFILE_NAME" ;;
  cluster) cluster "$PROFILE_NAME" ;;
  deploy-rules) deploy_rules ;;
  demo) demo "${2:-200}" ;;
  status) ctx get pods -A ;;
  down) kind delete cluster --name "$CLUSTER" ;;
  *) sed -n '2,10p' "$0" >&2; exit 2 ;;
esac
