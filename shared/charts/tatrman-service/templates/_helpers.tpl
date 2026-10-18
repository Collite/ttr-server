{{/*
Shared naming/label/image helpers. Keyed off the CONSUMING chart (.Chart.Name /
.Chart.Version / .Chart.AppVersion), so a module needs no _helpers.tpl of its own —
the library reproduces the exact output the per-module helpers used to emit.
*/}}

{{/* Chart name (overridable via nameOverride). */}}
{{- define "tatrman-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified app name. */}}
{{- define "tatrman-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "tatrman-service.name" . | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* Common labels. */}}
{{- define "tatrman-service.labels" -}}
app.kubernetes.io/name: {{ include "tatrman-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Selector labels (stable across upgrades). */}}
{{- define "tatrman-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tatrman-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Resolved image reference. Tag precedence: per-service `image.tag` (a module override)
→ umbrella-wide `global.image.tag` (the SV-P4 umbrella's single product-tag knob;
nil/absent for a standalone per-service deploy) → the chart's own `appVersion`. The
`global` read is nil-safe so a chart rendered on its own (no umbrella, no `global`)
falls straight through to appVersion — unchanged from before this fallback existed.
*/}}
{{- define "tatrman-service.image" -}}
{{- $globalTag := "" -}}
{{- with .Values.global -}}
{{- with .image -}}
{{- $globalTag = .tag | default "" -}}
{{- end -}}
{{- end -}}
{{- $tag := .Values.image.tag | default $globalTag | default .Chart.AppVersion -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{/*
Pod/container volume composition.

⚑ **Helm template names are GLOBAL across an umbrella render, and the last chart loaded
wins.** So a per-chart override of a shared define is only safe while exactly ONE chart in
the render overrides it. `tatrman-service.volumes` used to be an empty hook that charon
overrode wholesale; the moment a second chart needed a volume, one of the two silently lost
— which is precisely what happened when the lexicon mount (RV-P3.3) landed here: chrono,
money, geo and lex-matcher rendered their `*_LEXICON_ARCHIVE_PATH` env var pointing at a
path with **nothing mounted at it**, and the readers would have logged "archive missing"
and served an empty vocabulary. The env var is not the mount, and only a fixture that turns
both on catches the difference.

So the library now OWNS the `volumes:` / `volumeMounts:` keys and composes two contributors:

  1. the **compiled lexicon archive**, implemented here once rather than four times over,
     because four services mount the same artifact under one values contract — OFF unless
     `lexicon.configMapName` is set, so a chart that has never heard of a lexicon renders
     byte-identically to before;
  2. whatever a module adds through `tatrman-service.extraVolumes` /
     `.extraVolumeMounts` — ITEM-level hooks (list entries only, never the parent key), so
     two charts contributing in one render compose instead of one erasing the other.

`initContainers` stays a whole-block hook: nothing here contributes one, so there is no
composition to do and charon's plugin-dir override remains correct as written.

Item indentation belongs to the hook (8 spaces for volumes, 12 for volumeMounts) — the
library emits the key at the right depth and interpolates the items verbatim.
*/}}
{{- define "tatrman-service.extraVolumes" -}}{{- end -}}
{{- define "tatrman-service.extraVolumeMounts" -}}{{- end -}}
{{/*
ttr-server#209 — the lexicon ConfigMaps, in mount order: `lexicon.configMapName` first (the
pre-#209 key, unchanged), then every `lexicon.configMapNames` entry not already named. One per
source the estate's packages come from; the readers that take a list (resolver, lex-matcher) load
them as ONE declared layer. Emitted as a JSON array — `include` can only return a string.
*/}}
{{- define "tatrman-service.lexiconConfigMaps" -}}
{{- $names := list -}}
{{- with .Values.lexicon -}}
{{- if .configMapName }}{{ $names = append $names .configMapName }}{{ end -}}
{{- range (.configMapNames | default list) }}{{ if and . (not (has . $names)) }}{{ $names = append $names . }}{{ end }}{{ end -}}
{{- end -}}
{{- toJson $names -}}
{{- end -}}
{{- define "tatrman-service.lexiconEnabled" -}}
{{- if include "tatrman-service.lexiconConfigMaps" . | fromJsonArray }}true{{- end -}}
{{- end -}}
{{/*
NLS-P3.2 — rule-pack and gazetteer-list mounts, contributed here for exactly the
reason the ⚑ above gives. Only `nlp` mounts these, so a per-chart override of
`extraVolumes`/`extraVolumeMounts` would be the obvious home — except charon
already overrides both, and a shared define has one global winner per render. An
umbrella carrying charon and nlp would silently drop one chart's mounts, and the
symptom would be a service that starts, reports itself healthy, and serves an
empty vocabulary. Same trap, same fix as the lexicon: implemented once, behind a
values contract that is OFF unless set, so every chart that has never heard of a
rule pack renders byte-identically to before.

Two separate mounts rather than one, because packs and lists are separate configmaps
in practice: a world republishes its glossary-derived lists far more often than its
rule packs, and coupling them would make every list update a pack rollout.
*/}}
{{- define "tatrman-service.packsEnabled" -}}
{{- if and .Values.packs .Values.packs.configMapName }}true{{- end -}}
{{- end -}}
{{- define "tatrman-service.listsEnabled" -}}
{{- if and .Values.lists .Values.lists.configMapName }}true{{- end -}}
{{- end -}}
{{/*
IE-P2·S2.3 — the OVERLAY CONFIG FRAGMENT, the fourth contributor, and the mechanism
`services/dispatch/src/main/resources/application.conf` has been asking for since the
2026-07-19 TPC-DS collision: *"a future multi-connection deployment that genuinely needs
table-level routing should inject entries via an overlay-provided config (values-driven
mount/env), not the base conf."* There was no such mechanism. `extraEnv` cannot express
`world.table-connections { "db.dbo.investment_*" = "pg-entry" }` — the keys carry dots and
an asterisk, and nothing reads them from the environment.

So: a per-deployment HOCON fragment, mounted as a ConfigMap and handed to the JVM as
`-Dconfig.file`. Two things make it safe rather than a foot-gun:

  ⛔ **The chart writes the `include` line, not the operator.** `-Dconfig.file` REPLACES the
     classpath `application.conf` rather than merging with it, so a fragment that forgot
     `include classpath("application.conf")` would boot a service with ONLY the fragment —
     every default gone, and the failure looking like a dozen unrelated misconfigurations.
     The operator supplies the delta; `configFragmentConfigMap` prepends the include.

  ⛔ **It lives here, not in the module chart.** See the ⚑ above: Helm template names are
     global per render, so a module overriding `tatrman-service.extraVolumes` silently erases
     any other chart's contribution in the same umbrella. charon already overrides it. Same
     trap, same fix as the lexicon and the rule packs — implemented once, behind a values
     contract that is OFF unless `configFragment.content` is set, so every chart that has
     never heard of one renders byte-identically to before.
*/}}
{{- define "tatrman-service.configFragmentEnabled" -}}
{{- if and .Values.configFragment .Values.configFragment.content }}true{{- end -}}
{{- end -}}
{{- define "tatrman-service.configFragmentName" -}}
{{- printf "%s-config-fragment" (include "tatrman-service.fullname" .) -}}
{{- end -}}
{{/* The in-container path of the mounted fragment — the value of `-Dconfig.file`. */}}
{{- define "tatrman-service.configFragmentPath" -}}
{{- printf "%s/%s" (.Values.configFragment.mountPath | default "/etc/tatrman/conf.d" | trimSuffix "/") (.Values.configFragment.key | default "overlay.conf") -}}
{{- end -}}
{{/*
The JVM flag that makes the mount do anything. Emitted BESIDE the mount, from the same
condition, so the two cannot drift: an env var pointing at a path with nothing mounted at it
is the exact failure the lexicon ⚑ above records paying for four times over.
*/}}
{{- define "tatrman-service.configFragmentEnv" -}}
{{- if include "tatrman-service.configFragmentEnabled" . }}
- name: JAVA_TOOL_OPTIONS
  value: "-Dconfig.file={{ include "tatrman-service.configFragmentPath" . }}"
{{- end }}
{{- end -}}
{{/*
The ConfigMap itself. A module opts in with a one-line `templates/configmap.yaml` that
includes this; a library chart renders nothing on its own.
*/}}
{{- define "tatrman-service.configFragmentConfigMap" -}}
{{- if include "tatrman-service.configFragmentEnabled" . }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "tatrman-service.configFragmentName" . }}
  labels:
    {{- include "tatrman-service.labels" . | nindent 4 }}
data:
  {{ .Values.configFragment.key | default "overlay.conf" }}: |
    # Rendered by the tatrman-service chart. Do not edit in the cluster — the source is the
    # deployment's `configFragment.content`.
    #
    # `-Dconfig.file` REPLACES the classpath application.conf; this include is what keeps every
    # default. It is written here rather than left to the fragment author on purpose.
    include classpath("application.conf")

{{ .Values.configFragment.content | indent 4 }}
{{- end }}
{{- end -}}
{{/* The in-container path of the mounted archive — the value of every `*_LEXICON_ARCHIVE_PATH`. */}}
{{- define "tatrman-service.lexiconPath" -}}
{{- printf "%s/%s" (.Values.lexicon.mountPath | trimSuffix "/") .Values.lexicon.key -}}
{{- end -}}
{{/*
ttr-server#209 — where the Nth lexicon ConfigMap is mounted. The FIRST keeps `lexicon.mountPath`
exactly (so `lexiconPath` above, and every reader's singular env var, still names it); each further
one gets its own directory BESIDE it, `<mountPath>.d/<configMapName>`, never inside it — a mount
point cannot be created inside a read-only ConfigMap volume. Directory mounts, not subPath, so a
ConfigMap update reaches the file and the readers' refresh picks it up.
*/}}
{{- define "tatrman-service.lexiconMountPath" -}}
{{- $root := .root -}}
{{- if eq (int .index) 0 -}}
{{- $root.Values.lexicon.mountPath -}}
{{- else -}}
{{- printf "%s.d/%s" ($root.Values.lexicon.mountPath | trimSuffix "/") .name -}}
{{- end -}}
{{- end -}}
{{/*
ttr-server#209 — every mounted archive's in-container path, comma-separated, in mount order: the
value of the list env vars (`RESOLVER_LEXICON_ARCHIVE_PATHS`, `FUZZY_LEXICON_ARCHIVE_PATHS`). Its
first element is always `lexiconPath`.
*/}}
{{- define "tatrman-service.lexiconPaths" -}}
{{- $root := . -}}
{{- $paths := list -}}
{{- range $i, $name := (include "tatrman-service.lexiconConfigMaps" . | fromJsonArray) -}}
{{- $dir := include "tatrman-service.lexiconMountPath" (dict "root" $root "index" $i "name" $name) -}}
{{- $paths = append $paths (printf "%s/%s" ($dir | trimSuffix "/") $root.Values.lexicon.key) -}}
{{- end -}}
{{- join "," $paths -}}
{{- end -}}
{{/* How many lexicon ConfigMaps are mounted — the list env var is rendered only when it is more than one. */}}
{{- define "tatrman-service.lexiconCount" -}}
{{- len (include "tatrman-service.lexiconConfigMaps" . | fromJsonArray) -}}
{{- end -}}
{{- define "tatrman-service.volumeMounts" -}}
{{- $extra := include "tatrman-service.extraVolumeMounts" . }}
{{- $any := or (include "tatrman-service.lexiconEnabled" .) (include "tatrman-service.packsEnabled" .) (include "tatrman-service.listsEnabled" .) (include "tatrman-service.configFragmentEnabled" .) }}
{{- if or $any (trim $extra) }}
          volumeMounts:
{{- $lexRoot := . }}
{{- range $i, $name := (include "tatrman-service.lexiconConfigMaps" . | fromJsonArray) }}
            - name: {{ if eq $i 0 }}lexicon{{ else }}lexicon-{{ $i }}{{ end }}
              mountPath: {{ include "tatrman-service.lexiconMountPath" (dict "root" $lexRoot "index" $i "name" $name) }}
              readOnly: true
{{- end }}
{{- if include "tatrman-service.packsEnabled" . }}
            - name: packs
              mountPath: {{ .Values.packs.mountPath }}
              readOnly: true
{{- end }}
{{- if include "tatrman-service.listsEnabled" . }}
            - name: lists
              mountPath: {{ .Values.lists.mountPath }}
              readOnly: true
{{- end }}
{{- if include "tatrman-service.configFragmentEnabled" . }}
            - name: config-fragment
              mountPath: {{ .Values.configFragment.mountPath | default "/etc/tatrman/conf.d" }}
              readOnly: true
{{- end }}
{{- with $extra }}{{ . }}{{- end }}
{{- end }}
{{- end -}}
{{- define "tatrman-service.volumes" -}}
{{- $extra := include "tatrman-service.extraVolumes" . }}
{{- $any := or (include "tatrman-service.lexiconEnabled" .) (include "tatrman-service.packsEnabled" .) (include "tatrman-service.listsEnabled" .) (include "tatrman-service.configFragmentEnabled" .) }}
{{- if or $any (trim $extra) }}
      volumes:
{{- range $i, $name := (include "tatrman-service.lexiconConfigMaps" . | fromJsonArray) }}
        - name: {{ if eq $i 0 }}lexicon{{ else }}lexicon-{{ $i }}{{ end }}
          configMap:
            name: {{ $name }}
{{- end }}
{{- if include "tatrman-service.packsEnabled" . }}
        - name: packs
          configMap:
            name: {{ .Values.packs.configMapName }}
{{- end }}
{{- if include "tatrman-service.listsEnabled" . }}
        - name: lists
          configMap:
            name: {{ .Values.lists.configMapName }}
{{- end }}
{{- if include "tatrman-service.configFragmentEnabled" . }}
        - name: config-fragment
          configMap:
            name: {{ include "tatrman-service.configFragmentName" . }}
{{- end }}
{{- with $extra }}{{ . }}{{- end }}
{{- end }}
{{- end -}}
{{/* Optional pod initContainers override (renders the whole `initContainers:` block, or nothing). */}}
{{- define "tatrman-service.initContainers" -}}{{- end -}}
