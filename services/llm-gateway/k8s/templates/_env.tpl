{{/* llm-gateway container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "llm-gateway.env" -}}
- name: SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: POSTGRESQL_HOST
  value: {{ .Values.db.host | quote }}
- name: POSTGRESQL_PORT
  value: {{ .Values.db.port | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
{{- /*
  review-100 F7 — `telemetry.enabled` never reached the app: application.conf reads
  OTEL_ENABLED_LLM_GATEWAY, which this chart did not set, so the gateway ran OpenTelemetry.noop() on every
  estate — no spans, and no trace id on any prompt-log row (the protocol's by-trace read found nothing). And
  shared otel-config reads OTEL_EXPORTER_OTLP_HOST + OTEL_EXPORTER_OTLP_{GRPC,HTTP,HTTPS}_PORT, never
  OTEL_EXPORTER_OTLP_ENDPOINT (the resolver chart's note). Same shape as the resolver chart.
*/}}
- name: OTEL_ENABLED_LLM_GATEWAY
  value: {{ .Values.telemetry.enabled | quote }}
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
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
