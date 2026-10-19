{{/* Names and labels. One release per namespace: workloads are named after their chart, like the compose services. */}}
{{- define "cc-lib.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cc-lib.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cc-lib.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "cc-lib.labels" -}}
{{ include "cc-lib.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: cc-platform
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | quote }}
{{- end -}}

{{/* Image: global.image.registry + repository : tag (values tag, else global.image.tag, else appVersion). */}}
{{- define "cc-lib.image" -}}
{{- $g := .Values.global | default dict -}}
{{- $gi := $g.image | default dict -}}
{{- /* image.registry (even empty) wins over global.image.registry: third-party images set it to "" */ -}}
{{- $registry := ternary .Values.image.registry ($gi.registry | default "") (hasKey .Values.image "registry") -}}
{{- $tag := .Values.image.tag | default $gi.tag | default .Chart.AppVersion -}}
{{- if $registry -}}{{ printf "%s/%s:%s" (trimSuffix "/" $registry) .Values.image.repository $tag }}{{- else -}}{{ printf "%s:%s" .Values.image.repository $tag }}{{- end -}}
{{- end -}}

{{/* Secret with the store passwords (created by scripts/k8s-secrets.sh, or by Sealed Secrets / External Secrets). */}}
{{- define "cc-lib.pkiPasswordSecret" -}}
{{- (.Values.global | default dict).pki | default dict | dig "passwordSecret" "cc-pki-password" -}}
{{- end -}}

{{/* The port probes and metrics use: the management port when there is one, else the service port. */}}
{{- define "cc-lib.managementPort" -}}
{{- .Values.managementPort | default .Values.port -}}
{{- end -}}
