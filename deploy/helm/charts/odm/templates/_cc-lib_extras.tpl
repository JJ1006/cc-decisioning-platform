{{/* HorizontalPodAutoscaler (CPU), only when hpa.enabled. */}}
{{- define "cc-lib.hpa" -}}
{{- if and .Values.hpa .Values.hpa.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "cc-lib.name" . }}
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "cc-lib.name" . }}
  minReplicas: {{ .Values.hpa.minReplicas | default 1 }}
  maxReplicas: {{ .Values.hpa.maxReplicas | default 3 }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.hpa.cpu | default 80 }}
{{- end }}
{{- end -}}

{{/* PodDisruptionBudget, only when pdb.enabled (needs more than one replica to be useful). */}}
{{- define "cc-lib.pdb" -}}
{{- if and .Values.pdb .Values.pdb.enabled }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "cc-lib.name" . }}
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
spec:
  maxUnavailable: {{ .Values.pdb.maxUnavailable | default 1 }}
  selector:
    matchLabels: {{- include "cc-lib.selectorLabels" . | nindent 6 }}
{{- end }}
{{- end -}}

{{/*
  NetworkPolicy of the workload (the namespace is default-deny, see cc-infra).
  networkPolicy.ingress: list of {from: [peers], ports: [numbers]} -> allow those peers on those ports.
  networkPolicy.egress:  raw NetworkPolicyEgressRule list (kafka, odm, elasticsearch ...). DNS is always allowed.
  networkPolicy.allowPrometheus: Prometheus (namespace global.monitoring.namespace) may scrape the management port.
*/}}
{{- define "cc-lib.networkpolicy" -}}
{{- $np := .Values.networkPolicy | default dict -}}
{{- $g := .Values.global | default dict -}}
{{- $monNs := ($g.monitoring | default dict).namespace | default "monitoring" -}}
{{- if $np.enabled }}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "cc-lib.name" . }}
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels: {{- include "cc-lib.selectorLabels" . | nindent 6 }}
  policyTypes: [Ingress, Egress]
  ingress:
    {{- range $np.ingress | default list }}
    - from: {{- toYaml .from | nindent 8 }}
      ports:
        {{- range .ports }}
        - { port: {{ . }}, protocol: TCP }
        {{- end }}
    {{- end }}
    {{- if $np.allowPrometheus }}
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ $monNs }}
      ports:
        - { port: {{ include "cc-lib.managementPort" . }}, protocol: TCP }
    {{- end }}
  egress:
    - to:
        - namespaceSelector: {}
          podSelector:
            matchExpressions:
              - { key: k8s-app, operator: In, values: [kube-dns] }
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              dns.operator.openshift.io/daemonset-dns: default
      ports:
        - { port: 53, protocol: UDP }
        - { port: 53, protocol: TCP }
        - { port: 5353, protocol: UDP }
        - { port: 5353, protocol: TCP }
    {{- with $np.egress }}{{ toYaml . | nindent 4 }}{{ end }}
{{- end }}
{{- end -}}

{{/* ServiceMonitor (Prometheus Operator CRD; on OpenShift the user-workload monitoring stack reads it too). */}}
{{- define "cc-lib.servicemonitor" -}}
{{- $sm := .Values.serviceMonitor | default dict -}}
{{- if $sm.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "cc-lib.name" . }}
  labels:
    {{- include "cc-lib.labels" . | nindent 4 }}
    {{- with $sm.labels }}{{ toYaml . | nindent 4 }}{{ end }}
spec:
  selector:
    matchLabels: {{- include "cc-lib.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: {{ if and .Values.managementPort (ne (int .Values.managementPort) (int .Values.port)) }}metrics{{ else }}{{ ternary "https" "http" (default false .Values.tls) }}{{ end }}
      path: {{ $sm.path | default "/actuator/prometheus" }}
      interval: {{ $sm.interval | default "30s" }}
{{- end }}
{{- end -}}

{{/*
  Exposure, switched by global.exposure: "ingress" (kind, plain Kubernetes), "route" (OpenShift) or "none".
  expose: { enabled, host, port, termination: passthrough|reencrypt|edge, backendProtocol: HTTPS|HTTP, path }
*/}}
{{- define "cc-lib.expose" -}}
{{- $e := .Values.expose | default dict -}}
{{- $g := .Values.global | default dict -}}
{{- $mode := $g.exposure | default "none" -}}
{{- $name := include "cc-lib.name" . -}}
{{- if and $e.enabled (ne $mode "none") }}
{{- if eq $mode "route" }}
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: {{ $name }}
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
spec:
  {{- with $e.host }}
  host: {{ . }}
  {{- end }}
  to:
    kind: Service
    name: {{ $name }}
    weight: 100
  port:
    targetPort: {{ ternary "https" "http" (default false .Values.tls) }}
  tls:
    termination: {{ $e.termination | default "reencrypt" }}
    insecureEdgeTerminationPolicy: Redirect
    {{- if and (eq ($e.termination | default "reencrypt") "reencrypt") ($g.pki | default dict).caPem }}
    destinationCACertificate: |-
      {{- $g.pki.caPem | nindent 6 }}
    {{- end }}
  wildcardPolicy: None
{{- else }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ $name }}
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
  annotations:
    nginx.ingress.kubernetes.io/backend-protocol: {{ $e.backendProtocol | default (ternary "HTTPS" "HTTP" (default false .Values.tls)) | quote }}
    {{- if eq ($e.termination | default "") "passthrough" }}
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
    {{- end }}
spec:
  ingressClassName: {{ ($g.ingress | default dict).className | default "nginx" }}
  rules:
    - host: {{ $e.host | default (printf "%s.localtest.me" $name) }}
      http:
        paths:
          - path: {{ $e.path | default "/" }}
            pathType: Prefix
            backend:
              service:
                name: {{ $name }}
                port:
                  number: {{ .Values.port }}
{{- end }}
{{- end }}
{{- end -}}

{{/* Everything one workload needs. A subchart calls this from its templates/all.yaml. */}}
{{- define "cc-lib.all" -}}
{{- $parts := list
  (include "cc-lib.serviceaccount" .)
  (include "cc-lib.configmap" .)
  (include "cc-lib.certificate" .)
  (include "cc-lib.deployment" .)
  (include "cc-lib.service" .)
  (include "cc-lib.hpa" .)
  (include "cc-lib.pdb" .)
  (include "cc-lib.networkpolicy" .)
  (include "cc-lib.servicemonitor" .)
  (include "cc-lib.expose" .) -}}
{{- range $parts }}
{{- $p := . | trim }}
{{- if $p }}
---
{{ $p }}
{{- end }}
{{- end }}
{{- end -}}
