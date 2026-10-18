{{/* values: config { KEY: value } -> ConfigMap <name>-config (envFrom); files { name: content } -> ConfigMap <name>-files (mounted). */}}
{{- define "cc-lib.serviceaccount" -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "cc-lib.name" . }}
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
automountServiceAccountToken: false
{{- end -}}

{{- define "cc-lib.configmap" -}}
{{- if .Values.config }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "cc-lib.name" . }}-config
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
data:
  {{- range $k, $v := .Values.config }}
  {{ $k }}: {{ $v | toString | quote }}
  {{- end }}
{{- end }}
{{- if .Values.files }}
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "cc-lib.name" . }}-files
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
data:
  {{- range $k, $v := .Values.files }}
  {{ $k }}: |
    {{- $v | nindent 4 }}
  {{- end }}
{{- end }}
{{- end -}}
