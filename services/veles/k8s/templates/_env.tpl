{{/* veles container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "veles.env" -}}
{{- /* extraEnv may already carry the branch / sub-path (deploying envs did before these keys existed): never render a duplicate. */ -}}
{{- $extraNames := list -}}
{{- range .Values.extraEnv }}{{ $extraNames = append $extraNames .name }}{{ end -}}
- name: VELES_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
{{- if .Values.ports.grpc }}
- name: VELES_GRPC_PORT
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
