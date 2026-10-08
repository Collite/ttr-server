{{/* query-mcp container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "query-mcp.env" -}}
- name: QUERY_MCP_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: QUERY_MCP_REQUIRE_IDENTITY
  value: {{ .Values.requireIdentity | quote }}
{{- if .Values.auth.verifySignature }}
- name: QUERY_MCP_VERIFY_SIGNATURE
  value: "true"
- name: QUERY_MCP_AUTH_ISSUER
  value: {{ required "auth.issuer is required when auth.verifySignature is on" .Values.auth.issuer | quote }}
{{- with .Values.auth.jwksUri }}
- name: QUERY_MCP_AUTH_JWKS_URI
  value: {{ . | quote }}
{{- end }}
{{- with .Values.auth.audience }}
- name: QUERY_MCP_AUTH_AUDIENCE
  value: {{ . | quote }}
{{- end }}
{{- end }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_QUERY_MCP
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
