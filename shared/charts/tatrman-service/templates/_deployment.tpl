{{/*
Backend (Kotlin/Ktor service · agent · worker · Python service) Deployment.
The per-module container env block is delegated to `<module>.env` (defined in the
consuming chart). The gRPC port + its env are gated on `.Values.ports.grpc` so
HTTP-only modules omit them; `.Values.ports.extra` (`[{name, port}]`, empty by default) adds a module's own
listeners (the query service's Flight SQL edge). Optional volumeMounts/volumes come from the override
hooks (tatrman-service.volumeMounts / .volumes), empty unless a module needs them.
*/}}
{{- define "tatrman-service.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "tatrman-service.fullname" . }}
  labels:
    {{- include "tatrman-service.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "tatrman-service.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      {{- /* Roll the pods when the config fragment changes (review-089 ⒅). The service reads its
             config once at boot, so without this a fragment edit syncs green and changes nothing
             until a manual restart. Same pattern as `_fe.tpl`'s `checksum/config`: hash the
             rendered template, not a file path. Only when the fragment is on, so every chart
             without one renders byte-identically. */}}
      {{- $fragment := include "tatrman-service.configFragmentEnabled" . }}
      {{- if or $fragment .Values.podAnnotations }}
      annotations:
        {{- if $fragment }}
        checksum/config-fragment: {{ include "tatrman-service.configFragmentConfigMap" . | sha256sum }}
        {{- end }}
        {{- with .Values.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      {{- end }}
      labels:
        {{- include "tatrman-service.selectorLabels" . | nindent 8 }}
    spec:
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- include "tatrman-service.initContainers" . }}
      containers:
        - name: {{ .Chart.Name }}
          image: {{ include "tatrman-service.image" . | quote }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.ports.http }}
            {{- if .Values.ports.grpc }}
            - name: grpc
              containerPort: {{ .Values.ports.grpc }}
            {{- end }}
            {{- range .Values.ports.extra }}
            - name: {{ .name }}
              containerPort: {{ .port }}
            {{- end }}
          env:
            {{- include (printf "%s.env" .Chart.Name) . | nindent 12 }}
            {{- with (include "tatrman-service.configFragmentEnv" .) }}{{- . | nindent 12 }}{{- end }}
          {{- with .Values.envFrom }}
          envFrom:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- if .Values.startupProbe }}
          startupProbe:
            httpGet:
              path: {{ .Values.startupProbe.path }}
              port: {{ .Values.ports.http }}
            initialDelaySeconds: {{ .Values.startupProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.startupProbe.periodSeconds }}
            failureThreshold: {{ .Values.startupProbe.failureThreshold }}
          {{- end }}
          readinessProbe:
            httpGet:
              path: {{ .Values.readinessProbe.path }}
              port: {{ .Values.ports.http }}
            initialDelaySeconds: {{ .Values.readinessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.livenessProbe.path }}
              port: {{ .Values.ports.http }}
            initialDelaySeconds: {{ .Values.livenessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.livenessProbe.periodSeconds }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          {{- include "tatrman-service.volumeMounts" . }}
      {{- include "tatrman-service.volumes" . }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}
