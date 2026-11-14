# DDB API v3 — Helm

API + Gateway + Redis, standardmäßig je ein Pod. S3 und Identity Provider extern.
Konfiguration: Values-YAML und Secrets; keine zusätzliche Oberfläche.
`appProfile` setzt `APP_PROFILE`: standardmäßig `prod`, optional `dev`.

Voraussetzungen: Helm 3, Kubernetes ≥1.26/OpenShift, ein passender Worker-Node,
dynamische PVs und ein S3-Bucket. Secret `ddb-api-v3-s3` im Release-Namespace
vorab anlegen: `S3_ACCESS_KEY`, `S3_SECRET_KEY`.
[YAML und Rechte](../../docs/deployment.md#speicher-und-secrets).

## Installieren

`values-site.yaml`:

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
helm install ddb-api-v3 ddb-api-v3/ddb-api-v3 \
  --namespace ddb-api-v3 --create-namespace \
  --values values-site.yaml --wait --timeout 10m
```

Adressen ersetzen, Versionen pinnen, bei privaten Images `images.pullSecrets` setzen.
Issuer + Audience gemeinsam; ohne beide nur Lesebetrieb. `records:write` ist ein
privilegiertes Importrecht. [Auth](../../docs/security.md).

Uploadlimit: `apiService.maxDocumentSize`; Quoten: `gateway.rateLimit`.
`forwardHeadersStrategy` ist je Anwendung konfigurierbar. Alle ENV-Zuordnungen
stehen im [Schema](values.schema.json), das auch die Hilfetexte im nativen
OpenShift-Formular liefert. YAML bleibt verfügbar; seltene Optionen unter `extraEnv`.
Für einen Dev-Token aus einem Secret: [Deployment-Konfiguration](../../docs/deployment.md#values-und-installation).

## Kapazität

| Default | CPU-Request | RAM-Request / Limit |
| --- | --- | --- |
| API × 1 | 100m | 256Mi / 512Mi |
| Gateway × 1 | 50m | 128Mi / 256Mi |
| Redis × 1 | 25m | 32Mi / 128Mi |

Zusammen: 175m CPU, 416Mi RAM-Requests, 896Mi RAM-Limits und 1Gi PVC.
Kleine Startwerte; eine Einzelinstanz bietet keine Ausfallsicherheit.
Das [Produktionsprofil](values-production.yaml) ergänzt API × 3, Gateway × 2,
Redis/Sentinel × 3 und größere Pools. Für diese Installation drei Worker-Nodes vorsehen:

```bash
helm install ddb-api-v3 ./charts/ddb-api-v3 -n ddb-api-v3 --create-namespace \
  -f charts/ddb-api-v3/values-production.yaml -f values-site.yaml
```

HPA optional über `apiService.autoscaling.enabled` und `gateway.autoscaling.enabled`;
vertikal über `resources`. [Details und Grenzen](../../docs/deployment.md#skalierung-und-rollouts).

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
Redis läuft standardmäßig als StatefulSet. `redis.useDeploymentWhenNonHA: true`
wählt für die Einzelinstanz ein Deployment mit PVC und `Recreate`; HA verwendet
immer ein StatefulSet. Redis speichert Quoten, RDF bleibt in S3.
Das Redis-PDB verwendet standardmäßig `maxUnavailable: 1`; kein `minAvailable` ergänzen.

[Values](values.yaml) · [Deployment, Redis, Rollouts und OpenShift](../../docs/deployment.md) ·
[Images/Releases](../../docs/operations.md#container-und-releases)
