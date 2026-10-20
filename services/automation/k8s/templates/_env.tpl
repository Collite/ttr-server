{{/*
automation container env — AU contracts §8, as application.conf reads it (AUTOMATION_*). Secrets are
Secret refs only: the database password, the bundle repository's token and every TTR_CONN_<NAME>.
The four keys the service cannot start without fail the RENDER here, by name (`required`), so an
incomplete values file is a diff in olymp, not a crash loop on the cluster.
*/}}
{{- define "automation.env" -}}
- name: AUTOMATION_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: AUTOMATION_DB_HOST
  value: {{ required "automation: db.host is required" .Values.db.host | quote }}
- name: AUTOMATION_DB_PORT
  value: {{ .Values.db.port | quote }}
- name: AUTOMATION_DB_NAME
  value: {{ .Values.db.name | quote }}
- name: AUTOMATION_DB_USER
  value: {{ .Values.db.user | quote }}
- name: AUTOMATION_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "automation: db.passwordSecret.name is required" .Values.db.passwordSecret.name }}
      key: {{ .Values.db.passwordSecret.key }}
- name: AUTOMATION_DOOR_QUERY_URL
  value: {{ .Values.door.queryUrl | quote }}
- name: AUTOMATION_DOOR_VELES_URL
  value: {{ .Values.door.velesUrl | quote }}
{{- with .Values.door.sidecarUrl }}
- name: AUTOMATION_DOOR_SIDECAR_URL
  value: {{ . | quote }}
{{- end }}
- name: AUTOMATION_AUTH_JWKS_URL
  value: {{ required "automation: auth.jwksUrl is required" .Values.auth.jwksUrl | quote }}
- name: AUTOMATION_AUTH_ISSUER
  value: {{ required "automation: auth.issuer is required" .Values.auth.issuer | quote }}
{{- with .Values.auth.audience }}
- name: AUTOMATION_AUTH_AUDIENCE
  value: {{ . | quote }}
{{- end }}
{{- with .Values.auth.tokenUrl }}
- name: AUTOMATION_AUTH_TOKEN_URL
  value: {{ . | quote }}
{{- end }}
{{- with .Values.auth.authorMap }}
- name: AUTOMATION_AUTHOR_MAP
  value: {{ . | quote }}
{{- end }}
{{- with .Values.auth.refreshRoles }}
- name: AUTOMATION_AUTH_REFRESH_ROLES
  value: {{ join "," . | quote }}
{{- end }}
{{- with .Values.bundle.git.url }}
- name: AUTOMATION_BUNDLE_GIT_URL
  value: {{ . | quote }}
{{- end }}
- name: AUTOMATION_BUNDLE_GIT_BRANCH
  value: {{ .Values.bundle.git.branch | quote }}
{{- with .Values.bundle.git.path }}
- name: AUTOMATION_BUNDLE_GIT_PATH
  value: {{ . | quote }}
{{- end }}
{{- if .Values.bundle.git.tokenSecret.name }}
- name: AUTOMATION_BUNDLE_GIT_TOKEN
  valueFrom:
    secretKeyRef:
      name: {{ .Values.bundle.git.tokenSecret.name }}
      key: {{ .Values.bundle.git.tokenSecret.key }}
{{- end }}
- name: AUTOMATION_SCHEDULER_TZ
  value: {{ .Values.scheduler.tz | quote }}
- name: AUTOMATION_SCHEDULER_ENABLED
  value: {{ .Values.scheduler.enabled | quote }}
- name: AUTOMATION_PAUSED
  value: {{ .Values.paused | quote }}
{{- range .Values.credentials }}
- name: {{ printf "TTR_CONN_%s" .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secretName }}
      key: {{ .secretKey }}
{{- end }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_AUTOMATION
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.otlpHost }}
- name: OTEL_EXPORTER_OTLP_HOST
  value: {{ .Values.telemetry.otlpHost | quote }}
- name: OTEL_EXPORTER_OTLP_GRPC_PORT
  value: {{ .Values.telemetry.otlpGrpcPort | default 4317 | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
