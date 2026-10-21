{{/*
Backend ClusterIP Service. `http` always; `grpc` gated on `.Values.ports.grpc`
(HTTP-only modules leave it unset); each of `.Values.ports.extra` with its optional `appProtocol`
(`kubernetes.io/h2c` for a gRPC listener a gateway routes to). port == targetPort == the container port.
*/}}
{{- define "tatrman-service.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "tatrman-service.fullname" . }}
  labels:
    {{- include "tatrman-service.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector:
    {{- include "tatrman-service.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.ports.http }}
      targetPort: {{ .Values.ports.http }}
    {{- if .Values.ports.grpc }}
    - name: grpc
      port: {{ .Values.ports.grpc }}
      targetPort: {{ .Values.ports.grpc }}
    {{- end }}
    {{- range .Values.ports.extra }}
    - name: {{ .name }}
      port: {{ .port }}
      targetPort: {{ .port }}
      {{- with .appProtocol }}
      appProtocol: {{ . }}
      {{- end }}
    {{- end }}
{{- end -}}
