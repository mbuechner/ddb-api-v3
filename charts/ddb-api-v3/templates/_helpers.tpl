{{- define "ddb-api-v3.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ddb-api-v3.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "ddb-api-v3.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ddb-api-v3.labels" -}}
helm.sh/chart: {{ include "ddb-api-v3.chart" . }}
app.kubernetes.io/name: {{ include "ddb-api-v3.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "ddb-api-v3.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ddb-api-v3.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "ddb-api-v3.configMapName" -}}
{{- printf "%s-config" (include "ddb-api-v3.fullname" .) }}
{{- end }}

{{- define "ddb-api-v3.cassandraSecretName" -}}
{{- printf "%s-cassandra" (include "ddb-api-v3.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ddb-api-v3.redisSecretName" -}}
{{- required "redis.auth.secretName is required" .Values.redis.auth.secretName }}
{{- end }}

{{- define "ddb-api-v3.redisServerConfigSecretName" -}}
{{- required "redis.extraSecretRedisConfigs is required" .Values.redis.extraSecretRedisConfigs }}
{{- end }}

{{- define "ddb-api-v3.redisSentinelConfigSecretName" -}}
{{- required "redis.extraSecretSentinelConfigs is required" .Values.redis.extraSecretSentinelConfigs }}
{{- end }}

{{- define "ddb-api-v3.apiServiceName" -}}
{{- printf "%s-api-service" (include "ddb-api-v3.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ddb-api-v3.gatewayName" -}}
{{- printf "%s-gateway" (include "ddb-api-v3.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ddb-api-v3.redisName" -}}
{{- if contains "redis" .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-redis" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end }}

{{- define "ddb-api-v3.publicBaseUrl" -}}
{{- if .Values.apiService.publicBaseUrl -}}
{{- .Values.apiService.publicBaseUrl -}}
{{- else if .Values.ingress.enabled -}}
{{- ternary "https" "http" (ne .Values.ingress.tlsSecretName "") }}://{{ required "ingress.host is required" .Values.ingress.host }}
{{- else -}}
{{- required "apiService.publicBaseUrl is required when ingress.enabled=false" .Values.apiService.publicBaseUrl -}}
{{- end -}}
{{- end }}
