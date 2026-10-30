{{- define "worker-docproc.env" -}}
- name: DOCPROC_POOL
  value: {{ .Values.pool | quote }}
- name: DOCPROC_FAMILIES
  value: {{ .Values.families | quote }}
- name: DOCPROC_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
- name: PIPELINE_DB_URL
  valueFrom:
    secretKeyRef:
      name: {{ .Values.pipelineDb.secretName | quote }}
      key: {{ .Values.pipelineDb.secretKey | quote }}
- name: DOCWH_STORE_URL
  value: {{ .Values.storeUrl | quote }}
- name: DOCWH_PIPELINE_URL
  value: {{ .Values.pipelineUrl | quote }}
- name: NLP_GRPC
  value: {{ .Values.nlpGrpc | quote }}
- name: LEXMATCHER_GRPC
  value: {{ .Values.lexmatcherGrpc | quote }}
- name: GATEWAY_URL
  value: {{ .Values.gatewayUrl | quote }}
- name: DOCPROC_PACK_PATH
  value: {{ .Values.packPath | quote }}
- name: DOCPROC_TELEMETRY_ENABLED
  value: {{ .Values.telemetry.enabled | quote }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- if .Values.worldDb.secretName }}
- name: TTR_CONN_EXTRACT_RECORDS
  valueFrom:
    secretKeyRef:
      name: {{ .Values.worldDb.secretName | quote }}
      key: {{ .Values.worldDb.secretKey | quote }}
{{- end }}
{{- if .Values.serviceToken.secretName }}
- name: DOCPROC_SERVICE_TOKEN
  valueFrom:
    secretKeyRef:
      name: {{ .Values.serviceToken.secretName | quote }}
      key: {{ .Values.serviceToken.secretKey | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{ toYaml . }}
{{- end }}
{{- end -}}
