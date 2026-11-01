{{/* metadata container env — model source, optional CN store and legacy overrides. */}}
{{- define "metadata.env" -}}
{{- /* extraEnv may already carry the branch / sub-path (deploying envs did before these keys existed): never render a duplicate. */ -}}
{{- $extraNames := list -}}
{{- range .Values.extraEnv }}{{ $extraNames = append $extraNames .name }}{{ end -}}
- name: METADATA_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
{{- if .Values.ports.grpc }}
- name: METADATA_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
{{- end }}
- name: METADATA_GIT_REMOTE_URI
  value: {{ .Values.metadata.gitRemoteUri | quote }}
{{- if and .Values.metadata.gitBranch (not (has "METADATA_GIT_BRANCH" $extraNames)) }}
- name: METADATA_GIT_BRANCH
  value: {{ .Values.metadata.gitBranch | quote }}
{{- end }}
{{- if and .Values.metadata.gitSubdirectory (not (has "METADATA_GIT_SUBDIR" $extraNames)) }}
- name: METADATA_GIT_SUBDIR
  value: {{ .Values.metadata.gitSubdirectory | quote }}
{{- end }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_METADATA
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with (include "metadata.cnEnv" . | trim) }}
{{ . }}
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


{{/* CN keys map to existing runtime settings. A legacy value OR Secret ref wins over a generated entry. */}}
{{- define "metadata.cnEnv" -}}
{{- $legacy := concat (.Values.extraEnv | default list) (.Values.secretEnv | default list) -}}
{{- $names := list -}}
{{- range $legacy }}{{ $names = append $names .name }}{{ end -}}
{{- with .Values.cn -}}
{{- $values := dict
  "METADATA_CN_DB_HOST" .db.host
  "METADATA_CN_AUTH_ISSUER" .auth.issuer
  "METADATA_CN_AUTH_JWKS_URI" .auth.jwksUri
  "METADATA_CN_AUTH_AUDIENCE" .auth.audience
  "METADATA_CN_STEWARD_ROLE" .stewardRole
  "METADATA_CN_IMPORTER_ROLE" .importerRole
  "METADATA_CN_PRODUCERS" .producers
  "METADATA_CN_PROMOTION_SOURCE" .promotion.source
  "METADATA_CN_INBOX_DIR" .promotion.inboxDir
  "METADATA_CN_PR_REPOSITORY" .promotion.github.repository
  "METADATA_CN_PR_API_URL" .promotion.github.apiUrl
  "METADATA_CN_NOTIFY_URL" .notifyUrl -}}
{{- if .db.host -}}
{{- $_ := set $values "METADATA_CN_DB_PORT" (.db.port | toString) -}}
{{- $_ := set $values "METADATA_CN_DB_NAME" .db.name -}}
{{- $_ := set $values "METADATA_CN_DB_USER" .db.user -}}
{{- end -}}
{{- range $name, $value := $values -}}
{{- if and $value (not (has $name $names)) }}
- name: {{ $name }}
  value: {{ $value | quote }}
{{- end -}}
{{- end -}}
{{- range $name, $ref := dict "METADATA_CN_DB_PASSWORD" .db.passwordSecret "METADATA_CN_PR_TOKEN" .promotion.github.tokenSecret -}}
{{- if and $ref.name (not (has $name $names)) }}
- name: {{ $name }}
  valueFrom:
    secretKeyRef:
      name: {{ $ref.name | quote }}
      key: {{ required (printf "metadata: CN Secret key is required for %s" $name) $ref.key | quote }}
      optional: true
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
