{{/* query container env — SERVER_PORT/GRPC_PORT + OTel + the BI edge and worlds (when set) + downstream extraEnv. */}}
{{- define "query.env" -}}
- name: QUERY_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: QUERY_SERVER_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_QUERY
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.worlds }}
- name: QUERY_WORLDS
  value: {{ . | quote }}
{{- end }}
{{- if .Values.requireWorld }}
- name: QUERY_REQUIRE_WORLD
  value: "true"
{{- end }}
{{- if .Values.flight.enabled }}
- name: QUERY_FLIGHT_ENABLED
  value: "true"
- name: QUERY_FLIGHT_PORT
  value: {{ .Values.flight.port | quote }}
- name: QUERY_FLIGHT_BIND
  value: {{ .Values.flight.bind | quote }}
- name: QUERY_FLIGHT_AUTH_ISSUER
  value: {{ required "query: flight.auth.issuer is required when flight.enabled" .Values.flight.auth.issuer | quote }}
{{- with .Values.flight.auth.jwksUri }}
- name: QUERY_FLIGHT_AUTH_JWKS_URI
  value: {{ . | quote }}
{{- end }}
{{- with .Values.flight.auth.audience }}
- name: QUERY_FLIGHT_AUTH_AUDIENCE
  value: {{ . | quote }}
{{- end }}
{{- with .Values.flight.auth.tokenUrl }}
- name: QUERY_FLIGHT_KC_TOKEN_URL
  value: {{ . | quote }}
{{- end }}
- name: QUERY_FLIGHT_KC_CLIENT
  value: {{ .Values.flight.auth.passwordClient | quote }}
{{- with .Values.flight.receipts.db }}
{{- if .host }}
- name: QUERY_RECEIPTS_DB_HOST
  value: {{ .host | quote }}
- name: QUERY_RECEIPTS_DB_PORT
  value: {{ .port | quote }}
- name: QUERY_RECEIPTS_DB_NAME
  value: {{ .name | quote }}
- name: QUERY_RECEIPTS_DB_USER
  value: {{ .user | quote }}
{{- /* Optional: receipts are best effort (C-6), so a missing Secret turns them off (the service says so at start-up)
     instead of holding the whole door in CreateContainerConfigError. */}}
- name: QUERY_RECEIPTS_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "query: flight.receipts.db.passwordSecret.name is required with a receipts host" .passwordSecret.name }}
      key: {{ .passwordSecret.key }}
      optional: true
{{- end }}
{{- end }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
