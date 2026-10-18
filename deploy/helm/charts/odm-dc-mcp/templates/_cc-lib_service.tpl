{{/* Service: "https"/"http" on the service port, "metrics" on the management port when it differs. */}}
{{- define "cc-lib.service" -}}
{{- $type := (.Values.service | default dict).type | default "ClusterIP" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "cc-lib.name" . }}
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
spec:
  type: {{ $type }}
  selector: {{- include "cc-lib.selectorLabels" . | nindent 4 }}
  ports:
    - name: {{ ternary "https" "http" (default false .Values.tls) }}
      port: {{ .Values.port }}
      targetPort: {{ .Values.port }}
      {{- if and (eq $type "NodePort") (.Values.service | default dict).nodePort }}
      nodePort: {{ .Values.service.nodePort }}
      {{- end }}
    {{- if and .Values.managementPort (ne (int .Values.managementPort) (int .Values.port)) (ne (toString ((.Values.service | default dict).exposeManagement | default true)) "false") }}
    - name: metrics
      port: {{ .Values.managementPort }}
      targetPort: {{ .Values.managementPort }}
    {{- end }}
{{- end -}}
