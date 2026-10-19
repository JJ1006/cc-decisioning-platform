{{/*
  Deployment of one workload. Pod security: runAsNonRoot, NO fixed UID/GID or fsGroup (OpenShift restricted-v2 assigns them; set
  securityContext.runAsUser only on a plain cluster for an image whose user is a name), all capabilities dropped, no privilege escalation,
  read-only root file system with emptyDirs for the paths in writablePaths, seccomp RuntimeDefault, no service account token.
*/}}
{{- define "cc-lib.deployment" -}}
{{- $name := include "cc-lib.name" . -}}
{{- $g := .Values.global | default dict -}}
{{- $sc := .Values.securityContext | default dict -}}
{{- $mgmt := include "cc-lib.managementPort" . -}}
{{- $pki := .Values.pki | default dict -}}
{{- $kafka := .Values.kafka | default dict -}}
{{- $otel := .Values.otel | default dict -}}
{{- $probes := .Values.probes | default dict -}}
{{- $autoscaled := and .Values.hpa .Values.hpa.enabled -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels: {{- include "cc-lib.labels" . | nindent 4 }}
  {{- with .Values.annotations }}
  annotations: {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  {{- if not $autoscaled }}
  replicas: {{ .Values.replicas | default 1 }}
  {{- end }}
  revisionHistoryLimit: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels: {{- include "cc-lib.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "cc-lib.labels" . | nindent 8 }}
        {{- with .Values.podLabels }}{{ toYaml . | nindent 8 }}{{ end }}
      annotations:
        {{- if .Values.config }}
        checksum/config: {{ toYaml .Values.config | sha256sum }}
        {{- end }}
        {{- if .Values.files }}
        checksum/files: {{ toYaml .Values.files | sha256sum }}
        {{- end }}
        {{- if $otel.enabled }}
        resource.opentelemetry.io/service.name: {{ $name }}
        resource.opentelemetry.io/service.namespace: cc
        {{- end }}
        {{- with .Values.podAnnotations }}{{ toYaml . | nindent 8 }}{{ end }}
    spec:
      serviceAccountName: {{ $name }}
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: {{ .Values.terminationGracePeriodSeconds | default 30 }}
      securityContext:
        runAsNonRoot: true
        {{- with $sc.runAsUser }}
        runAsUser: {{ . }}
        {{- end }}
        seccompProfile:
          type: RuntimeDefault
      {{- with (.Values.imagePullSecrets | default $g.imagePullSecrets) }}
      imagePullSecrets: {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.nodeSelector }}
      nodeSelector: {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations: {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- if gt (int (.Values.replicas | default 1)) 1 }}
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels: {{- include "cc-lib.selectorLabels" . | nindent 14 }}
      {{- end }}
      {{- with .Values.initContainers }}
      initContainers: {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ $name }}
          image: {{ include "cc-lib.image" . | quote }}
          imagePullPolicy: {{ .Values.image.pullPolicy | default "IfNotPresent" }}
          {{- with .Values.command }}
          command: {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- with .Values.args }}
          args: {{- toYaml . | nindent 12 }}
          {{- end }}
          ports:
            - name: {{ ternary "https" "http" (default false .Values.tls) }}
              containerPort: {{ .Values.port }}
            {{- if and .Values.managementPort (ne (int .Values.managementPort) (int .Values.port)) }}
            - name: metrics
              containerPort: {{ .Values.managementPort }}
            {{- end }}
          env:
            {{- if $otel.enabled }}
            - name: K8S_POD_NAME
              valueFrom: { fieldRef: { fieldPath: metadata.name } }
            - name: K8S_NAMESPACE
              valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
            - name: OTEL_SERVICE_NAME
              value: {{ $name }}
            - name: OTEL_RESOURCE_ATTRIBUTES
              value: "deployment.environment={{ ($g.otel | default dict).environment | default "kubernetes" }},service.namespace=cc,k8s.pod.name=$(K8S_POD_NAME),k8s.namespace.name=$(K8S_NAMESPACE)"
            - name: OTEL_SDK_DISABLED
              value: {{ not (($g.otel | default dict).enabled | default false) | quote }}
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: {{ ($g.otel | default dict).endpoint | default "http://otel-collector:4318" | quote }}
            - name: OTEL_TRACES_SAMPLER
              value: {{ ($g.otel | default dict).sampler | default "parentbased_always_on" | quote }}
            {{- end }}
            {{- if $kafka.enabled }}
            - name: KAFKA_BOOTSTRAP
              value: {{ ($g.kafka | default dict).bootstrap | default "cc-kafka-bootstrap:9093" | quote }}
            {{- if eq ($kafka.format | default "pkcs12") "pem" }}
            - name: KAFKA_CA_FILE
              value: /kafka/ca.crt
            - name: KAFKA_CERT
              value: /kafka/user.crt
            - name: KAFKA_CERT_KEY
              value: /kafka/user.key
            {{- else }}
            - name: KAFKA_KEYSTORE
              value: /kafka/user.p12
            - name: KAFKA_TRUSTSTORE
              value: /kafka/ca.p12
            - name: KAFKA_STORE_PASSWORD
              valueFrom:
                secretKeyRef: { name: {{ $kafka.userSecret | default $name }}, key: user.password }
            - name: KAFKA_TRUSTSTORE_PASSWORD
              valueFrom:
                secretKeyRef: { name: {{ ($g.kafka | default dict).clusterCaSecret | default "cc-cluster-ca-cert" }}, key: ca.password }
            {{- end }}
            {{- end }}
            {{- range .Values.pkiPasswordEnv | default list }}
            - name: {{ . }}
              valueFrom:
                secretKeyRef: { name: {{ include "cc-lib.pkiPasswordSecret" $ }}, key: password }
            {{- end }}
            {{- range $k, $v := .Values.env }}
            - name: {{ $k }}
              value: {{ $v | toString | quote }}
            {{- end }}
            {{- range .Values.secretEnv | default list }}
            - name: {{ .name }}
              valueFrom:
                secretKeyRef: { name: {{ .secret }}, key: {{ .key }} }
            {{- end }}
            {{- with .Values.extraEnv }}{{ toYaml . | nindent 12 }}{{ end }}
          {{- if .Values.config }}
          envFrom:
            - configMapRef: { name: {{ $name }}-config }
          {{- end }}
          {{- if $probes.raw }}
          {{- toYaml $probes.raw | nindent 10 }}
          {{- else if dig "enabled" true $probes }}
          startupProbe:
            httpGet: { path: {{ $probes.startupPath | default "/actuator/health/liveness" }}, port: {{ $mgmt }} }
            periodSeconds: 5
            failureThreshold: {{ $probes.startupFailureThreshold | default 60 }}
          livenessProbe:
            httpGet: { path: {{ $probes.livenessPath | default "/actuator/health/liveness" }}, port: {{ $mgmt }} }
            periodSeconds: 15
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet: { path: {{ $probes.readinessPath | default "/actuator/health/readiness" }}, port: {{ $mgmt }} }
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
          {{- end }}
          resources: {{- toYaml .Values.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: {{ ternary $sc.readOnlyRootFilesystem true (hasKey $sc "readOnlyRootFilesystem") }}
            capabilities:
              drop: [ALL]
          volumeMounts:
            {{- range .Values.writablePaths | default (list "/tmp") }}
            - name: {{ . | trimPrefix "/" | replace "/" "-" | replace "_" "-" | default "root" }}
              mountPath: {{ . }}
            {{- end }}
            {{- if $pki.enabled }}
            - name: pki
              mountPath: {{ $pki.mountPath | default "/pki" }}
              readOnly: true
            {{- end }}
            {{- if $kafka.enabled }}
            - name: kafka
              mountPath: /kafka
              readOnly: true
            {{- end }}
            {{- range $k, $v := .Values.files }}
            - name: files
              mountPath: {{ $.Values.filesMountPath | default "/config" }}/{{ $k }}
              subPath: {{ $k }}
              readOnly: true
            {{- end }}
            {{- with .Values.extraVolumeMounts }}{{ toYaml . | nindent 12 }}{{ end }}
      volumes:
        {{- range .Values.writablePaths | default (list "/tmp") }}
        - name: {{ . | trimPrefix "/" | replace "/" "-" | replace "_" "-" | default "root" }}
          emptyDir: {{- if $.Values.emptyDirSizeLimit }} { sizeLimit: {{ $.Values.emptyDirSizeLimit }} }{{ else }} {}{{ end }}
        {{- end }}
        {{- if $pki.enabled }}
        - name: pki
          projected:
            defaultMode: 0444
            sources:
              {{- if $pki.sources }}
              {{- toYaml $pki.sources | nindent 14 }}
              {{- else if eq ($pki.format | default "pkcs12") "pem" }}
              - secret:
                  name: {{ $name }}-tls
                  items:
                    - { key: tls.crt, path: tls.crt }
                    - { key: tls.key, path: tls.key }
                    - { key: ca.crt, path: ca.crt }
              {{- else }}
              - secret:
                  name: {{ $name }}-tls
                  items:
                    - { key: keystore.p12, path: keystore.p12 }
                    - { key: truststore.p12, path: truststore.p12 }
                    - { key: ca.crt, path: ca.crt }
              {{- end }}
              {{- range $pki.extra | default list }}
              - secret:
                  name: {{ .name }}-tls
                  items:
                    - { key: tls.crt, path: {{ .name }}.crt }
                    - { key: tls.key, path: {{ .name }}.key }
              {{- end }}
        {{- end }}
        {{- if $kafka.enabled }}
        - name: kafka
          projected:
            defaultMode: 0444
            sources:
              - secret:
                  name: {{ $kafka.userSecret | default $name }}
                  items:
                    {{- if eq ($kafka.format | default "pkcs12") "pem" }}
                    - { key: user.crt, path: user.crt }
                    - { key: user.key, path: user.key }
                    {{- else }}
                    - { key: user.p12, path: user.p12 }
                    {{- end }}
              - secret:
                  name: {{ ($g.kafka | default dict).clusterCaSecret | default "cc-cluster-ca-cert" }}
                  items:
                    {{- if eq ($kafka.format | default "pkcs12") "pem" }}
                    - { key: ca.crt, path: ca.crt }
                    {{- else }}
                    - { key: ca.p12, path: ca.p12 }
                    {{- end }}
        {{- end }}
        {{- if .Values.files }}
        - name: files
          configMap:
            name: {{ $name }}-files
            defaultMode: 0444
        {{- end }}
        {{- with .Values.extraVolumes }}{{ toYaml . | nindent 8 }}{{ end }}
{{- end -}}
