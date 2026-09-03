# DDB API v3 Helm Chart

Der Chart installiert die vollständige Anwendung:

```text
Ingress
   |
Gateway Deployment ---- Redis Sentinel Service
   |                         |
API-Service             3 Redis-Nodes
   |                    (1 Primary, 2 Replicas,
Cassandra                je 1 Sentinel-Sidecar)
```

Gateway und API-Service lassen sich unabhängig horizontal skalieren. Alle
Gateway-Pods verwenden denselben, von Sentinel ermittelten Redis-Primary.

Der Chart installiert noch keinen OAuth2 Resource Server. Im Profil `prod`
akzeptiert das Gateway nur einen bereits authentifizierten Principal und sperrt
`/3/**` andernfalls mit `403 Forbidden`. Vor einem öffentlichen Betrieb muss die
Authentifizierung im Gateway ergänzt werden. Der aktuelle fachliche
Implementierungsumfang ist im Projekt-README beschrieben.

Redis wird nicht selbst im DDB-Chart implementiert. Der als Abhängigkeit
fixierte Groundhog2k-Redis-Chart übernimmt Replikation, Sentinel, Failover,
Probes und Persistenz. Er startet dafür das unveränderte Docker-Official-Image
`docker.io/library/redis:8.8.0`.

## Konfiguration

Im Normalfall müssen nur Images, Cassandra und der öffentliche Host angepasst
werden:

Standardmäßig verwendet der Chart die öffentlichen GHCR-Pakete
`ghcr.io/mbuechner/ddb-api-v3-service:latest` und
`ghcr.io/mbuechner/ddb-api-v3-gateway:latest`. Veröffentlichte Chart-Pakete sind
stattdessen fest mit den unveränderlichen `sha-<Commit>`-Tags verbunden.

```yaml
images:
  apiService:
    repository: registry.example.org/ddb/ddb-api-v3-service
    tag: "1.0.0"
  gateway:
    repository: registry.example.org/ddb/ddb-api-v3-gateway
    tag: "1.0.0"

cassandra:
  contactPoints: cassandra.database.svc:9042
  localDatacenter: datacenter1
  keyspace: ddb_api_v3
  username: ddb_api
  password: geheim

ingress:
  host: api.example.org
  tlsSecretName: api-example-org-tls
```

Redis-Adressen, Sentinel-Mastername und Authentifizierung setzt der Chart
automatisch. Die öffentliche API-Basis-URL wird aus Host und TLS-Konfiguration
abgeleitet. Ist der Ingress deaktiviert, muss `apiService.publicBaseUrl`
explizit gesetzt werden.

Seltene Spring-Einstellungen können weiterhin gezielt überschrieben werden:

```yaml
gateway:
  extraEnv:
    RATE_LIMIT_BURST_CAPACITY: "50"
apiService:
  extraEnv:
    DDB_API_MAX_RECORDS: "20000"
```

## Redis Sentinel

Der Subchart startet drei Redis-Nodes. Einer ist Primary, zwei sind Replicas;
in jedem Pod läuft zusätzlich ein Sentinel. Das Quorum beträgt zwei. Harte
Pod-Anti-Affinität verteilt die drei Pods auf unterschiedliche Kubernetes-Nodes.
Das Cluster benötigt daher mindestens drei passende Worker-Nodes.

Das Gateway verwendet ausschließlich das Profil `prod`. Der Chart setzt Spring
Boots native Sentinel-Einstellungen und verbindet es so über den internen
Sentinel-Service. Lettuce ermittelt darüber den aktuellen Primary und verbindet
nach einem Failover neu. Sentinel erhöht
die Verfügbarkeit, nicht den Schreibdurchsatz; alle Schreibzugriffe verwenden
weiterhin genau einen Primary.

Die internen Standardwerte sind:

- Ausfallerkennung nach 5 Sekunden
- Failover-Timeout 60 Sekunden
- AOF mit `appendfsync everysec`
- `maxmemory 256mb` und `noeviction`

Redis-Ressourcen und Speicher lassen sich direkt über `redis.resources`,
`redis.sentinelResources`, `redis.haMode` und `redis.storage` anpassen.

## Secrets und Persistenz

Der DDB-Chart erzeugt die Cassandra- und Redis-Secrets. Das zufällige
Redis-Passwort schützt den Gateway-Benutzer und Sentinel und wird bei Upgrades
aus dem vorhandenen Secret übernommen. Eine NetworkPolicy erlaubt Zugriffe auf
Redis und Sentinel nur von den Redis-Pods und den Gateway-Pods desselben
Namespaces.

Die Secrets tragen `helm.sh/resource-policy: keep`. Die Redis-StatefulSet-PVCs
verwenden außerdem `whenDeleted: Retain` und `whenScaled: Retain`. Secrets,
Redis-Daten und Sentinel-Zustand bleiben deshalb nach `helm uninstall`
erhalten. Für eine erneute Installation sollten Release-Name und Namespace
gleich bleiben. Eine endgültige Löschung erfolgt bewusst separat.

## OpenShift

Für Redis sind keine festen Benutzer-, Gruppen- oder `fsGroup`-IDs gesetzt.
OpenShift kann sie deshalb aus dem zulässigen Namespace-Bereich zuweisen. Auch
die DDB-Deployments geben keine feste UID oder GID vor.

Zusätzlich gelten:

- keine privilegierten DDB-Container
- keine Linux-Capabilities in den DDB-Containern
- schreibgeschützte Root-Dateisysteme
- keine Service-Account-Tokens in den DDB- oder Redis-Pods
- keine fest eingestellte IngressClass

Der Standard-Ingress kann vom OpenShift-Router verarbeitet werden.

Für eine manuelle Installation mit dem OpenShift-Client zuerst anmelden und ein
Projekt auswählen oder anlegen:

```bash
oc login https://api.cluster.example.org:6443 --token="$OPENSHIFT_TOKEN"
oc project ddb-api-v3 || oc new-project ddb-api-v3

helm repo add ddb-api-v3 https://mbuechner.github.io/ddb-api-v3
helm repo update ddb-api-v3
helm upgrade --install ddb-api-v3 ddb-api-v3/ddb-api-v3 \
  --namespace ddb-api-v3 \
  --set-string cassandra.contactPoints=cassandra.database.svc:9042 \
  --set-string cassandra.username="$CASSANDRA_USERNAME" \
  --set-string cassandra.password="$CASSANDRA_PASSWORD" \
  --set-string ingress.host=api.example.org \
  --wait --timeout 10m

oc rollout status deployment/ddb-api-v3-api-service -n ddb-api-v3
oc rollout status deployment/ddb-api-v3-gateway -n ddb-api-v3
oc get pods,services,ingress -n ddb-api-v3
```

## Installation und Prüfung

Der Pages-Workflow veröffentlicht den Chart als Helm-Repository und trägt beim
Paketieren automatisch die beiden GHCR-Repositories sowie den unveränderlichen
`sha-<Commit>`-Tag ein. Er startet nach dem erfolgreichen Container-Workflow und
prüft vor der Veröffentlichung beide Image-Manifeste. Nach Aktivierung von
**Settings → Pages → Source:
GitHub Actions** kann der veröffentlichte Chart installiert werden:

```bash
helm repo add ddb-api-v3 https://mbuechner.github.io/ddb-api-v3
helm repo update
helm upgrade --install ddb-api-v3 ddb-api-v3/ddb-api-v3 \
  --namespace ddb-api-v3 --create-namespace \
  --values values-production.yaml
```

GHCR-Packages müssen öffentlich lesbar sein. Bei privaten Packages ist vor der
Installation ein Registry-Secret anzulegen und über `images.pullSecrets` zu
referenzieren.

Der lokale Chart kann weiterhin direkt installiert werden. Die Redis-Abhängigkeit
ist in `Chart.yaml` auf das Groundhog2k-Repository konfiguriert und in
`Chart.lock` exakt fixiert. Das Abhängigkeitsarchiv wird nicht versioniert;
`helm dependency build` lädt es vor der lokalen Verwendung herunter. Der
Pages-Workflow erledigt diesen Schritt beim Paketieren automatisch.

Das erzeugte Helm-Paket enthält nur Chart-Templates und keine Container-Images.
Alle Pods verwenden `imagePullPolicy: Always` und beziehen ihre Images beim
Start aus den konfigurierten Registries.

```bash
helm repo add groundhog2k https://groundhog2k.github.io/helm-charts/
helm dependency build charts/ddb-api-v3

helm upgrade --install ddb-api-v3 ./charts/ddb-api-v3 \
  --namespace ddb-api-v3 --create-namespace \
  --values values-production.yaml

helm lint charts/ddb-api-v3
helm template ddb-api-v3 charts/ddb-api-v3 --namespace ddb-api-v3
```

Nach der Installation lassen sich Rollout und Sentinel-Zustand direkt prüfen.

### Kubernetes mit kubectl

Für einen Kubernetes-Cluster werden die Prüfungen mit `kubectl` ausgeführt:

```bash
kubectl rollout status statefulset/ddb-api-v3-redis --namespace ddb-api-v3
kubectl get pods --namespace ddb-api-v3 -l app.kubernetes.io/name=redis

kubectl exec --namespace ddb-api-v3 ddb-api-v3-redis-0 -c redis-sentinel -- \
  sh -c 'REDISCLI_AUTH="$(sed -n "s/^requirepass //p" \
  /data/conf/sentinel.conf)" \
  redis-cli -p 26379 SENTINEL get-master-addr-by-name ddb-api-v3'
```

### OpenShift mit oc

Unter OpenShift werden dieselben Prüfungen mit dem `oc`-Client ausgeführt:

```bash
oc rollout status statefulset/ddb-api-v3-redis -n ddb-api-v3
oc get pods -n ddb-api-v3 -l app.kubernetes.io/name=redis

oc exec -n ddb-api-v3 ddb-api-v3-redis-0 -c redis-sentinel -- \
  sh -c 'REDISCLI_AUTH="$(sed -n "s/^requirepass //p" \
  /data/conf/sentinel.conf)" \
  redis-cli -p 26379 SENTINEL get-master-addr-by-name ddb-api-v3'
```
