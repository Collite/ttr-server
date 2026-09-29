{{/* nlp container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "nlp.env" -}}
- name: UVICORN_PORT
  value: {{ .Values.ports.http | quote }}
- name: NLP_SERVICE_PORT
  value: {{ .Values.ports.http | quote }}
{{- if .Values.ports.grpc }}
- name: NLP_SERVICE_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
{{- end }}
{{- if .Values.lane }}
- name: NLP_LANE
  value: {{ .Values.lane | quote }}
{{- end }}
{{- /*
  RV-P8 — LLM_EMULATED. Rendered only when explicitly enabled, so a chart that
  says nothing about emulation leaves the image's own `enabled: false` standing:
  the off state is the absence of these vars, not a var set to "false" — which
  is also why this block must render NOTHING when off, not even a blank line
  (the golden-template gate is the thing that noticed).
  The virtual key is NEVER a value — it rides `secretEnv` like every other
  credential (`NLP_LLM_EMULATED_API_KEY`).
*/ -}}
{{- if .Values.llmEmulated.enabled }}
- name: NLP_LLM_EMULATED_ENABLED
  value: "true"
- name: NLP_LLM_EMULATED_URL
  value: {{ required "llmEmulated.gatewayUrl is required when llmEmulated.enabled" .Values.llmEmulated.gatewayUrl | quote }}
{{- if .Values.llmEmulated.model }}
- name: NLP_LLM_EMULATED_MODEL
  value: {{ .Values.llmEmulated.model | quote }}
{{- end }}
{{- end }}
{{- if and .Values.telemetry.enabled (not .Values.telemetry.otlpHost) }}
{{- fail "nlp: telemetry.enabled is true but telemetry.otlpHost is empty; name the collector, or leave telemetry off" }}
{{- end }}
{{- if and .Values.telemetry.enabled .Values.telemetry.otlpHost }}
- name: OTEL_EXPORTER_OTLP_HOST
  value: {{ .Values.telemetry.otlpHost | quote }}
- name: OTEL_EXPORTER_OTLP_GRPC_PORT
  value: {{ .Values.telemetry.otlpGrpcPort | quote }}
- name: NLP_SERVICE_OTEL_PROTOCOL
  value: {{ .Values.telemetry.protocol | quote }}
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
