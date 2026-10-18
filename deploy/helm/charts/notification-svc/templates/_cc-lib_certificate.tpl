{{/*
  cert-manager Certificate of the workload, issued by the platform CA (a ClusterIssuer, global.pki.issuer).
  pki.format pkcs12: secret <name>-tls holds the PKCS12 key and trust stores (password from the cc-pki-password secret), tls.crt, tls.key, ca.crt.
  pki.format jks:    like pkcs12 with Java key and trust stores (ODM / Liberty).
  pki.format pem:    the same without the stores.
  pki.extra: more client certificates the workload presents ({name, commonName}) -> secret <name>-tls.
*/}}
{{- define "cc-lib.certificate" -}}
{{- $root := . -}}
{{- $issuer := (((.Values.global | default dict).pki) | default dict) | dig "issuer" "cc-ca" -}}
{{- $ns := .Release.Namespace -}}
{{- if and .Values.pki .Values.pki.enabled }}
{{- $certs := list (dict "name" (include "cc-lib.name" .) "commonName" (.Values.pki.commonName | default (include "cc-lib.name" .)) "dns" true) -}}
{{- range .Values.pki.extra | default list }}{{ $certs = append $certs (dict "name" .name "commonName" .commonName "dns" false) }}{{ end -}}
{{- range $i, $c := $certs }}
{{- if $i }}
---
{{- end }}
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: {{ $c.name }}
  labels: {{- include "cc-lib.labels" $root | nindent 4 }}
spec:
  secretName: {{ $c.name }}-tls
  commonName: {{ $c.commonName }}
  {{- if $c.dns }}
  dnsNames:
    - {{ $c.name }}
    - {{ $c.name }}.{{ $ns }}
    - {{ $c.name }}.{{ $ns }}.svc
    - {{ $c.name }}.{{ $ns }}.svc.cluster.local
    - localhost
    {{- range $root.Values.pki.dnsNames | default list }}
    - {{ . }}
    {{- end }}
  {{- end }}
  usages: [digital signature, key encipherment, server auth, client auth]
  duration: {{ $root.Values.pki.duration | default "2160h" }}
  renewBefore: {{ $root.Values.pki.renewBefore | default "360h" }}
  privateKey:
    algorithm: RSA
    size: 2048
    rotationPolicy: Always
    encoding: PKCS8
  {{- if has ($root.Values.pki.format | default "pkcs12") (list "pkcs12" "jks") }}
  keystores:
    {{ $root.Values.pki.format | default "pkcs12" }}:
      create: true
      passwordSecretRef:
        name: {{ include "cc-lib.pkiPasswordSecret" $root }}
        key: password
  {{- end }}
  issuerRef:
    name: {{ $issuer }}
    kind: ClusterIssuer
    group: cert-manager.io
{{- end }}
{{- end }}
{{- end -}}
