{{/*
resolver container env — the HTTP/gRPC/MCP ports, the nlp + fuzzy gRPC targets, and the
HMAC resume-token wiring. Env-agnostic: deploying environments override the hosts/ports,
telemetry, and the resume-token key material (secretEnv). The `resolve.bind:v1` MCP door
binds loopback by default (RG-P6 review D) — a deploying env sets RESOLVER_MCP_HOST=0.0.0.0
only behind an auth-terminating ingress.
*/}}
{{- define "resolver.env" -}}
- name: RESOLVER_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
{{- if .Values.ports.grpc }}
- name: RESOLVER_SERVER_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
{{- end }}
- name: RESOLVER_MCP_PORT
  value: {{ .Values.mcp.port | quote }}
- name: RESOLVER_MCP_HOST
  value: {{ .Values.mcp.host | quote }}
- name: RESOLVER_MCP_REQUIRE_IDENTITY
  value: {{ .Values.mcp.requireIdentity | quote }}
- name: RESOLVER_MCP_TRUST_NETWORK
  value: {{ .Values.mcp.trustNetwork | quote }}
- name: NLP_HOST
  value: {{ .Values.nlp.host | quote }}
- name: NLP_GRPC_PORT
  value: {{ .Values.nlp.grpcPort | quote }}
{{- if .Values.nlp.deadlineSeconds }}
- name: NLP_GRPC_DEADLINE_SECONDS
  value: {{ .Values.nlp.deadlineSeconds | quote }}
{{- end }}
- name: FUZZY_HOST
  value: {{ .Values.fuzzy.host | quote }}
- name: FUZZY_GRPC_PORT
  value: {{ .Values.fuzzy.grpcPort | quote }}
{{- if .Values.fuzzy.deadlineSeconds }}
- name: FUZZY_GRPC_DEADLINE_SECONDS
  value: {{ .Values.fuzzy.deadlineSeconds | quote }}
{{- end }}
{{- with .Values.resumeToken.activeKeyId }}
- name: RESOLVER_RESUME_ACTIVE_KEY_ID
  value: {{ . | quote }}
{{- end }}
- name: RESOLVER_RESUME_MAX_AGE_SECONDS
  value: {{ .Values.resumeToken.maxAgeSeconds | quote }}
- name: RESOLVER_RESUME_ALLOW_EPHEMERAL_KEY
  value: {{ .Values.resumeToken.allowEphemeralKey | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_RESOLVER
  value: {{ .Values.telemetry.enabled | quote }}
{{- /*
  TG-P0-F1 — the collector address, as `shared.otel.OtelConfig` ACTUALLY reads it: a HOST and a
  PORT, from which it builds the URL itself (`OtelEndpointConfig.hostEnvVar` defaults to
  OTEL_EXPORTER_OTLP_HOST; the port comes from OTEL_EXPORTER_OTLP_{GRPC,HTTP,HTTPS}_PORT).
  It never reads OTEL_EXPORTER_OTLP_ENDPOINT.

  ⚠ So `telemetry.endpoint` below has never reached the exporters. It went unnoticed because no
  tatrman-server service on any cluster had telemetry enabled — the resolver is the first, and it
  would have silently exported to localhost:4317. Kantheon's charts got this right and say so:
  "The otel-config lib reads host + grpc port separately (not a single endpoint URL)."
  The same latent bug is in every other service's k8s _env.tpl; fixing those is not this change.
*/}}
{{- if .Values.telemetry.enabled }}
{{- if .Values.telemetry.otlpHost }}
- name: OTEL_EXPORTER_OTLP_HOST
  value: {{ .Values.telemetry.otlpHost | quote }}
- name: OTEL_EXPORTER_OTLP_GRPC_PORT
  value: {{ .Values.telemetry.otlpGrpcPort | default 4317 | quote }}
{{- end }}
{{- if .Values.telemetry.endpoint }}
{{- /* kept only so an existing values file does not silently lose a setting; unread by the lib */}}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- end }}
{{- range .Values.secretEnv }}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secretName }}
      key: {{ .secretKey }}
      {{- if .optional }}
      optional: {{ .optional }}
      {{- end }}
{{- end }}
{{- if include "tatrman-service.lexiconEnabled" . }}
{{- /*
  ttr-server#209 — the singular var ALWAYS names the FIRST archive, so an image older than the list
  key still boots on it during a rolling deploy; the list var names every archive and is rendered
  only when there is more than one, so a single-archive render is byte-identical to before.
*/}}
- name: RESOLVER_LEXICON_ARCHIVE_PATH
  value: {{ include "tatrman-service.lexiconPath" . | quote }}
{{- if gt (int (include "tatrman-service.lexiconCount" .)) 1 }}
- name: RESOLVER_LEXICON_ARCHIVE_PATHS
  value: {{ include "tatrman-service.lexiconPaths" . | quote }}
{{- end }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
