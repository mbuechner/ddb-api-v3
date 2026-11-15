# DDB API v3 — Helm

API + Gateway + Redis, standardmäßig je ein Pod. S3 und Identity Provider extern.
Konfiguration: Values-YAML und Secrets; keine zusätzliche Oberfläche.
`appProfile` setzt `APP_PROFILE`: standardmäßig `prod`, optional `dev`.

Voraussetzungen: Helm 3, Kubernetes ≥1.26/OpenShift, ein passender Worker-Node,
dynamische PVs und ein S3-Bucket. Secret `ddb-api-v3-s3` im Release-Namespace
vorab anlegen: `S3_ACCESS_KEY`, `S3_SECRET_KEY`.
[YAML und Rechte](../../docs/deployment.md#speicher-und-secrets).

Die Anwendungen erhalten getrennte ENV-Namen: `DDB_API_*` für API, S3 und
API-Logging, `GATEWAY_*` für Routing, Rate-Limits, Redis und Gateway-Logging.
`APP_PROFILE`, `DDB_DEV_WRITE_TOKEN` und `OAUTH2_*` sind gemeinsam.
Vorhandene Secret-Datenschlüssel bleiben unverändert: Beispielsweise wird
`S3_ACCESS_KEY` als `DDB_API_S3_ACCESS_KEY` und `REDIS_PASSWORD` als
`GATEWAY_REDIS_PASSWORD` injiziert. Native `SPRING_DATA_REDIS_SENTINEL_*`-Namen
bleiben für Boots Sentinel-Erkennung erhalten. Die Values-Struktur bleibt gleich;
anwendungsspezifische `extraEnv`-Einträge auf die neuen Präfixe umstellen.

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

| Default | CPU-Request / Limit | RAM-Request / Limit |
| --- | --- | --- |
| API × 1 | 100m / 500m | 256Mi / 512Mi |
| Gateway × 1 | 50m / 200m | 128Mi / 256Mi |
| Redis × 1 | 25m / 200m | 32Mi / 128Mi |

Zusammen (Request / Limit): 175m / 900m CPU, 416Mi / 896Mi RAM und 1Gi PVC.
Kleine Startwerte; eine Einzelinstanz bietet keine Ausfallsicherheit.
Das [Produktionsprofil](values-production.yaml) ergänzt API × 3, Gateway × 2,
Redis/Sentinel × 3 und größere Pools, zusammen 580m / 2950m CPU.
Für diese Installation drei Worker-Nodes vorsehen:

```bash
helm install ddb-api-v3 ./charts/ddb-api-v3 -n ddb-api-v3 --create-namespace \
  -f charts/ddb-api-v3/values-production.yaml -f values-site.yaml
```

HPA optional über `apiService.autoscaling.enabled` und `gateway.autoscaling.enabled`;
vertikal über `resources`. Optionale VPA-Empfehlungen für API, Gateway und Redis:
jeweils `vpa.enabled: true`, bei installiertem VPA-Controller/CRD. Modus `Off`
liefert nur CPU-/RAM-Empfehlungen und verändert keine Pods oder Limits.
CPU erst nach Messung unter echter Last erhöhen.
[Details und Grenzen](../../docs/deployment.md#skalierung-und-rollouts).

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
