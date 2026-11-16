# DDB API v3 — Helm

API + Gateway + Redis, standardmäßig je ein Pod. S3 und Identity Provider extern.
Konfiguration: Values-YAML und Secrets; keine zusätzliche Oberfläche.
`appProfile` setzt `APP_PROFILE`: standardmäßig `prod`, optional `dev`.

Voraussetzungen für die Standardinstallation: Helm 3, OpenShift mit ImageStream-API,
VPA-Controller und `VerticalPodAutoscaler`-CRD, ein passender Worker-Node,
dynamische PVs und ein S3-Bucket. Secret `ddb-api-v3-s3` im Release-Namespace
vorab anlegen: `S3_ACCESS_KEY`, `S3_SECRET_KEY`.
[YAML und Rechte](../../docs/deployment.md#speicher-und-secrets).

Die Anwendungen erhalten getrennte ENV-Namen: `DDB_API_*` für API, S3 und
API-Logging, `GATEWAY_*` für Routing, Rate-Limits, Redis und Gateway-Logging.
`APP_PROFILE`, `DDB_DEV_WRITE_TOKEN` und `OAUTH2_*` sind gemeinsam.
Vorhandene Secret-Datenschlüssel bleiben unverändert: Beispielsweise wird
`S3_ACCESS_KEY` als `DDB_API_S3_ACCESS_KEY` und `REDIS_PASSWORD` als
`GATEWAY_REDIS_PASSWORD` injiziert. Native `SPRING_DATA_REDIS_SENTINEL_*`-Namen
bleiben für Boots Sentinel-Erkennung erhalten. Anwendungsspezifische
`extraEnv`-Einträge auf die neuen Präfixe umstellen.

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

Adressen ersetzen; bei privaten Images Registry-Secrets für Image-Import und
Pod-Pulls im Namespace bereitstellen und `images.pullSecrets` setzen.
Issuer + Audience gemeinsam; ohne beide nur Lesebetrieb. `records:write` und
`items:write` sind privilegierte Importrechte für den jeweiligen Ressourcentyp.
[Auth](../../docs/security.md).

Uploadlimit: `apiService.maxDocumentSize`; Quoten: `gateway.rateLimit`.
`forwardHeadersStrategy` ist je Anwendung konfigurierbar. Alle ENV-Zuordnungen
stehen im [Schema](values.schema.json), das auch die Hilfetexte im nativen
OpenShift-Formular liefert. YAML bleibt verfügbar; seltene Optionen unter `extraEnv`.
Für einen Dev-Token aus einem Secret: [Deployment-Konfiguration](../../docs/deployment.md#values-und-installation).

## ImageStreams und automatische Updates

Standardmäßig bindet OpenShift alle Container über ImageStreams ein: API, Gateway
und Redis sowie den Redis-Exporter, wenn er aktiviert ist. Redis-Init- und
Sentinel-Container verwenden denselben Redis-Stream. Geplante Imports beobachten
die konfigurierten Registry-Tags; ein neuer importierter Digest aktualisiert die
Pod-Templates über Image-Change-Trigger und löst den jeweiligen Rollout aus.

API und Gateway verfolgen standardmäßig `latest`, Redis `8-alpine`. Die Quellen
bleiben über `images.apiService`, `images.gateway`, `redis.image` und
`redis.metrics.exporter.image` konfigurierbar. Importiert werden Registry-Metadaten;
Container-Binaries bleiben in der Registry und sind nicht Bestandteil des Charts.
Updates erfolgen nach dem im Cluster eingestellten Importintervall, nicht sofort
beim Push. `global.openshift.imageStreams.scheduled: false` schaltet die periodische
Prüfung ab. Für bewusst feste Anwendungsstände einen unveränderlichen Quell-Tag wählen.

Die API-/Gateway-Streamnamen folgen dem Release-Namen. Der unveränderte Redis-
Unterchart erhält dagegen explizite Values: `redis.imageStreams.name` ist
standardmäßig `ddb-api-v3-redis`, `exporterName` ist `ddb-api-v3-redis-exporter`.
Diese Namen müssen pro Namespace eindeutig sein. Bei einer Änderung auch die
`from.name`-Einträge in `redis.customAnnotations.image.openshift.io/triggers`
auf die neuen Namen mit Suffix `:latest` umstellen.

Redis-HA und Exporter fügen Container hinzu. Die mitgelieferten Overlays halten
die feste Triggerliste passend zu den tatsächlich vorhandenen Container-Pfaden:

| Redis-Modus | Zusätzlich zu den Basis-Values laden |
| --- | --- |
| Standalone | Kein Overlay erforderlich |
| HA mit Sentinel | `values-production.yaml` |
| Standalone mit Exporter | `values-metrics.yaml` |
| HA mit Sentinel und Exporter | `values-production.yaml`, danach `values-production-metrics.yaml` |

Nur `redis.haMode.enabled` oder `redis.metrics.enabled` umzuschalten reicht bei
aktiven ImageStreams nicht: Eine unpassende Triggerliste wird beim Rendern
abgewiesen. Es werden weder Redis-Chart-Quellen verändert noch Helper überschrieben.
Redis-StatefulSets benötigen dafür `redis.updateStrategyType: RollingUpdate`;
`OnDelete` würde bestehende Pods auf dem alten Image belassen und wird abgewiesen.
Verwaltet werden die integrierten Init-, Server-, Sentinel- und Exporter-Container.
Eigene `redis.extraContainers`/`extraInitContainers` erfordern deaktivierte
ImageStream-Verwaltung und eine eigene Image-Anbindung.

Reines Kubernetes ab 1.26 kann die direkte Registry-Anbindung verwenden. Fehlt auch
ein VPA-Controller, diese Opt-outs gemeinsam setzen:

```yaml
global:
  openshift:
    imageStreams:
      enabled: false
apiService:
  vpa: {enabled: false}
gateway:
  vpa: {enabled: false}
redis:
  vpa: {enabled: false}
  customAnnotations:
    image.openshift.io/triggers: "[]"
```

Damit entfallen ImageStreams und automatische Image-Change-Rollouts; die Pods
verwenden weiterhin die konfigurierten Registry-Images. Die leere Redis-Triggerliste
deaktiviert auch die expliziten Image-Trigger des unveränderten Untercharts.

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
vertikal über `resources`. VPAs sind für API, Gateway und Redis standardmäßig aktiv
und erfassen alle Replikate des jeweiligen Controllers. Redis berücksichtigt auch
Sentinel und den optionalen Exporter. Modus `Off` liefert nur CPU-/RAM-Empfehlungen
(`RequestsOnly`) und verändert keine Pods oder Limits.
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
Teilzeiten sind mit `logging.performanceEnabled: true` aktiv; die Slow-Markierung
beginnt ab `logging.slowRequestThreshold: 100ms`. Dieselbe `X-Request-Id` verbindet
Gateway und API. [Latenzdiagnose](../../docs/latency-diagnostics.md).
`apiService`/`gateway`: getrennte Replikazahlen, Ressourcen und HPA.
Redis-PVCs und verwaltete Redis-Secrets bleiben nach `helm uninstall` erhalten.
Redis läuft standardmäßig als StatefulSet. `redis.useDeploymentWhenNonHA: true`
wählt für die Einzelinstanz ein Deployment mit PVC und `Recreate`; HA verwendet
immer ein StatefulSet. Redis speichert Quoten, RDF bleibt in S3.
Das Redis-PDB verwendet standardmäßig `maxUnavailable: 1`; kein `minAvailable` ergänzen.

[Values](values.yaml) · [Deployment, Redis, Rollouts und OpenShift](../../docs/deployment.md) ·
[Images/Releases](../../docs/operations.md#container-und-releases)
