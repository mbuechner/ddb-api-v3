{{/* Redis's upstream chart renders annotations literally, so its stream names are explicit values. */}}
{{- define "ddb-api-v3.imageStreamName" -}}
{{- if eq .component "redis" -}}
{{- required "redis.imageStreams.name is required when ImageStreams are enabled" .root.Values.redis.imageStreams.name -}}
{{- else if eq .component "redis-exporter" -}}
{{- required "redis.imageStreams.exporterName is required when Redis metrics and ImageStreams are enabled" .root.Values.redis.imageStreams.exporterName -}}
{{- else -}}
{{- $suffix := .component -}}
{{- $prefixLength := sub 62 (len $suffix) | int -}}
{{- printf "%s-%s" (.root.Release.Name | trunc $prefixLength | trimSuffix "-") $suffix -}}
{{- end -}}
{{- end -}}

{{- define "ddb-api-v3.imageTrigger" -}}
{{- $stream := include "ddb-api-v3.imageStreamName" (dict "root" .root "component" .component) -}}
{{- $path := printf "spec.template.spec.%s[?(@.name==\"%s\")].image" .containerType .container -}}
{{- dict "from" (dict "kind" "ImageStreamTag" "name" (printf "%s:latest" $stream)) "fieldPath" $path "paused" false | toJson -}}
{{- end -}}

{{/* Expected Redis triggers, evaluated in the parent scope only to validate the literal values. */}}
{{- define "ddb-api-v3.redisImageTriggers" -}}
{{- $triggers := list -}}
{{- if .Values.global.openshift.imageStreams.enabled -}}
{{- range $container := list "redis-init" "redis-server" -}}
{{- $type := ternary "initContainers" "containers" (eq $container "redis-init") -}}
{{- $trigger := include "ddb-api-v3.imageTrigger" (dict "root" $ "component" "redis" "containerType" $type "container" $container) | fromJson -}}
{{- $triggers = append $triggers $trigger -}}
{{- end -}}
{{- if .Values.redis.haMode.enabled -}}
{{- $trigger := include "ddb-api-v3.imageTrigger" (dict "root" . "component" "redis" "containerType" "containers" "container" "redis-sentinel") | fromJson -}}
{{- $triggers = append $triggers $trigger -}}
{{- end -}}
{{- if .Values.redis.metrics.enabled -}}
{{- $trigger := include "ddb-api-v3.imageTrigger" (dict "root" . "component" "redis-exporter" "containerType" "containers" "container" "redis-exporter") | fromJson -}}
{{- $triggers = append $triggers $trigger -}}
{{- end -}}
{{- end -}}
{{- $triggers | toJson -}}
{{- end -}}
