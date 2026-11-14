# DDB API v3 — Helm

API + Gateway + Redis/Sentinel. S3 und Identity Provider extern.
Konfiguration: Values-YAML und Secrets; keine zusätzliche Oberfläche.

Voraussetzungen: Helm 3, Kubernetes ≥1.26/OpenShift, drei passende Worker-Nodes,
dynamische PVs und ein S3-Bucket. Secret `ddb-api-v3-s3` im Release-Namespace
vorab anlegen: `S3_ACCESS_KEY`, `S3_SECRET_KEY`.
[YAML und Rechte](../../docs/deployment.md#speicher-und-secrets).

## Installieren

`values-production.yaml`:

```yaml
s3:
  endpoint: https://s3.storage.example.org
  bucket: ddb-records
  existingSecret: ddb-api-v3-s3

ingress:
  host: api.example.org
  tlsSecretName: api-example-org-tls

apiService:
  baseUrl: https://api.example.org

auth:
  issuerUri: https://identity.example.org/realms/ddb
  audience: ddb-api-v3
```

```bash
helm repo add ddb-api-v3 https://mbuechner.github.io/ddb-api-v3
helm repo update ddb-api-v3
helm upgrade --install ddb-api-v3 ddb-api-v3/ddb-api-v3 \
  --namespace ddb-api-v3 --create-namespace \
  --values values-production.yaml --wait --timeout 10m
```

Adressen ersetzen, Versionen pinnen, bei privaten Images `images.pullSecrets` setzen.
Issuer + Audience gemeinsam; ohne beide nur Lesebetrieb. `records:write` ist ein
privilegiertes Importrecht. [Auth](../../docs/security.md) · [Upgrade auf 0.7](../../docs/deployment.md#upgrade-auf-07).

## Betrieb

```bash
kubectl get pods,services,ingress -n ddb-api-v3
kubectl rollout status deployment/ddb-api-v3-api-service -n ddb-api-v3
kubectl rollout status deployment/ddb-api-v3-gateway -n ddb-api-v3
```

OpenShift: `oc` statt `kubectl`. HTTPS am Ingress; API und Redis intern.
Nach S3-Secret-Rotation API-Deployment neu starten. Logs: stdout; Probes vorkonfiguriert.
`apiService`/`gateway`: getrennte Replikazahlen, Ressourcen und HPA.
Redis-PVCs und verwaltete Redis-Secrets bleiben nach `helm uninstall` erhalten.

[Values](values.yaml) · [Deployment, Redis, Rollouts und OpenShift](../../docs/deployment.md) ·
[Images/Releases](../../docs/operations.md#container-und-releases)
