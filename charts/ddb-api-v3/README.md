# DDB API v3 Helm Chart

Der Chart installiert die Anwendung und Redis; SeaweedFS wird extern betrieben:

```text
Ingress
   |
Gateway Deployment ---- Redis Sentinel Service
   |                         |
API-Service             3 Redis-Nodes
   |                    (1 Primary, 2 Replicas,
SeaweedFS S3             je 1 Sentinel-Sidecar)
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

Im Normalfall müssen nur Images, S3-Speicher und der öffentliche Host angepasst
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

s3:
  endpoint: https://seaweedfs.storage.svc:8333
  region: eu-central-1
  bucket: ddb-records
  existingSecret: ddb-api-v3-s3-credentials

ingress:
  host: api.example.org
  tlsSecretName: api-example-org-tls

apiService:
  baseUrl: https://api.example.org
```

Redis-Adressen, Sentinel-Mastername und Authentifizierung setzt der Chart
automatisch. Host und TLS-Konfiguration des Ingress bestimmen den öffentlichen
Einstiegspunkt. `apiService.baseUrl` legt unabhängig davon die Basis für Named
Graphs fest und wird als `DDB_API_BASE_URL` an die Anwendung übergeben. Standard
ist `https://api.deutsche-digitale-bibliothek.de`. Erlaubt ist ein HTTP(S)-Origin
mit optionalem Port und abschließendem Slash; Request-Header verändern die Basis
nicht. Eine Änderung gilt für spätere `POST`- und `PUT`-Schreibzugriffe. Bereits
gespeicherte Graphnamen bleiben beim Lesen erhalten.

Seltene Spring-Einstellungen können weiterhin gezielt überschrieben werden:

```yaml
gateway:
  extraEnv:
    RATE_LIMIT_BURST_CAPACITY: "50"
apiService:
  extraEnv:
    DDB_API_UPLOAD_MAX_FILE_SIZE: "2MB"
```

## SeaweedFS S3

Record-Daten liegen ausschließlich in einem extern betriebenen SeaweedFS-S3-
Bucket. Der Chart installiert keinen einzelnen Storage-Pod als Ersatz für einen
produktiven Storage-Cluster. Endpunkt, Region und Bucket stehen unter `s3`;
`pathStyleAccess: true` ermöglicht den Zugriff ohne DNS-Eintrag pro Bucket.
`connectionTimeout` und `readTimeout` begrenzen die S3-Aufrufe.

Den Bucket vor der Installation anlegen und der API-Identität Lese- und Schreibrechte
für dessen RDF-Objekte sowie Zugriff für `HeadBucket` geben.
`createBucket` bleibt in Produktion standardmäßig `false`. Der Storage-Endpunkt
muss atomare bedingte PUTs mit `If-Match` und `If-None-Match` unterstützen; die
lokale Entwicklungsumgebung ist auf SeaweedFS 4.53 fixiert. Die
[SeaweedFS-Dokumentation](https://github.com/seaweedfs/seaweedfs/wiki/S3-Conditional-Operations)
beschreibt diese Operationen. Die Readiness-Probe des API-Service prüft den
Bucket-Zugriff über den Health-Indikator `s3Storage`.

Empfohlen ist `s3.existingSecret` mit einem vorab im Release-Namespace angelegten
Secret. Es muss die Schlüssel `S3_ACCESS_KEY` und `S3_SECRET_KEY` enthalten.
Alternativ erzeugt der Chart aus `s3.accessKey` und `s3.secretKey` selbst ein
Secret. Zugangsdaten stehen ausschließlich im Secret und nicht in der ConfigMap.
Bei chartverwalteten Zugangsdaten führen geänderte Values zu einem neuen Secret
und einem automatischen Rollout der API-Pods. Bei `existingSecret` nach einer
Rotation selbst `kubectl rollout restart deployment/<API-Deployment>` ausführen:
bereits laufende Prozesse lesen geänderte Secret-Umgebungsvariablen nicht neu.

RDF wird vor jedem Speichern mit Apache Jena validiert. `Content-Type` wählt
das Eingabeformat; alle Triples werden im Named Graph
`{apiService.baseUrl}/records/{recordId}` vereinigt, standardmäßig
`https://api.deutsche-digitale-bibliothek.de/records/{recordId}`, und als
unkomprimiertes Jelly RDF (`application/x-jelly-rdf`) direkt unter
`{recordId}/record` gespeichert. `GET` liest Jelly und liefert über `Accept`
ein von Jena unterstütztes Ausgabeformat; ohne Vorgabe TriG. Weitere Namen, etwa
`{recordId}/item`, werden durch künftig implementierte API-Methoden festgelegt;
sie sind kein frei wählbarer Parameter der aktuellen Record-API. Es gibt kein
internes Metadatenmodell und keine zusätzlichen JSON-Manifeste. Die Region ist
standardmäßig `eu-central-1`. Details stehen im
[Projekt-README](../../README.md#speicherung-in-seaweedfs). Eine Migration aus
rohen RDF/XML-Objekten oder dem früheren Manifest-/Blob-Layout muss vor dem
Umschalten gesondert erfolgen.

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

Der DDB-Chart erzeugt das Redis-Secret und optional ein S3-Secret. Bei
`s3.existingSecret` verwaltet dessen Eigentümer die S3-Zugangsdaten; andernfalls
verwendet der Chart die expliziten S3-Values und rollt bei Änderungen die API-Pods.
Zur Rotation müssen Storage-Konto und die verwendeten Kubernetes-Zugangsdaten
zusammenpassen.
Das zufällige Redis-Passwort schützt den Gateway-Benutzer und Sentinel und wird
ebenfalls aus dem vorhandenen Secret übernommen. Eine NetworkPolicy erlaubt
Zugriffe auf Redis und Sentinel nur von den Redis-Pods und den Gateway-Pods
desselben Namespaces.

Die vom Chart erzeugten Secrets tragen `helm.sh/resource-policy: keep`. Die Redis-StatefulSet-PVCs
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
  --set-string s3.endpoint=https://seaweedfs.storage.svc:8333 \
  --set-string s3.existingSecret=ddb-api-v3-s3-credentials \
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

Veröffentlichte Pakete verwenden stabile Versionen wie `0.6.42001`:
Major und Minor stammen aus `Chart.yaml`, den Patch berechnet CI als
`Workflow-Laufnummer * 1000 + Ausführungsversuch`. Die Quell-Patchnummer wird
beim Paketieren ersetzt. Versuche sind auf 1 bis 999 begrenzt, damit auch
Wiederholungen eine eindeutige Version erhalten. Normale Helm-Aufrufe wählen
diese Pakete ohne Zusatzoption aus; das Pages-Repository enthält jeweils den
zuletzt veröffentlichten Stand.

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

helm lint charts/ddb-api-v3 --set-string s3.existingSecret=ddb-api-v3-s3-credentials
helm template ddb-api-v3 charts/ddb-api-v3 --namespace ddb-api-v3 \
  --set-string s3.existingSecret=ddb-api-v3-s3-credentials
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
