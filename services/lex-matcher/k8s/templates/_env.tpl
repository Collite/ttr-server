{{/* fuzzy container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "fuzzy.env" -}}
- name: FUZZY_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
{{- if .Values.ports.grpc }}
- name: FUZZY_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
{{- end }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_FUZZY
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
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
- name: FUZZY_LEXICON_ARCHIVE_PATH
  value: {{ include "tatrman-service.lexiconPath" . | quote }}
{{- if gt (int (include "tatrman-service.lexiconCount" .)) 1 }}
- name: FUZZY_LEXICON_ARCHIVE_PATHS
  value: {{ include "tatrman-service.lexiconPaths" . | quote }}
{{- end }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
