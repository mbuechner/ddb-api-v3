# Kubernetes und OpenShift

Die Kurzinstallation steht im [Chart-README](../charts/ddb-api-v3/README.md).
Alle Einstellungen erfolgen über YAML, Secrets und Helm; eine zusätzliche
Konfigurationsoberfläche ist nicht erforderlich. Der Chart startet beide
Anwendungen standardmäßig im Profil `prod`, je einen API-, Gateway- und Redis-Pod sowie einen Ingress.
Die kleine Standardinstallation benötigt keinen Sentinel und keine drei Worker-Nodes.
S3-Speicher und Identity Provider bleiben externe Dienste.

## Speicher und Secrets

Vor der Installation einen Namespace, den S3-Bucket und das Servicekonto anlegen.
Der Speicher muss atomare bedingte PUTs unterstützen. Das Konto braucht
Objekt-Lese-/Schreibrechte und `HeadBucket`-Zugriff; automatische Bucket-Anlage
bleibt in Produktion ausgeschaltet. Standardregion ist `eu-central-1`.

Das S3-Secret wird außerhalb von Helm verwaltet. Im Release-Namespace eine
private Datei anlegen und mit echten Kontowerten befüllen; nicht einchecken:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ddb-api-v3-s3
  namespace: ddb-api-v3
type: Opaque
stringData:
  S3_ACCESS_KEY: <AWS_ACCESS_KEY_ID-des-Servicekontos>
  S3_SECRET_KEY: <AWS_SECRET_ACCESS_KEY-des-Servicekontos>
```

```bash
kubectl create namespace ddb-api-v3
kubectl apply -f s3-secret.yaml
```

Unter OpenShift `oc new-project ddb-api-v3` und `oc apply -f s3-secret.yaml`
verwenden. `AWS_ENDPOINT_URL` des Kontos wird als `s3.endpoint` gesetzt;
`s3.existingSecret` verweist auf den Secret-Namen. Die Secret-Schlüsselnamen
lassen sich an vorhandene Servicekonten anpassen:

```yaml
s3:
  existingSecret: storage-account
  secretKeys:
    accessKey: AWS_ACCESS_KEY_ID
    secretKey: AWS_SECRET_ACCESS_KEY
```

Secret-Namen folgen Kubernetes-DNS-Regeln: Kleinbuchstaben, Ziffern, `-` und `.`,
jeweils mit Buchstabe oder Ziffer am Anfang und Ende; maximal 253 Zeichen.
Das gilt auch für Dev-Token-, TLS- und Registry-Secrets. Die ausgewählten
Schlüssel im Secret, etwa `AWS_ACCESS_KEY_ID`, dürfen Großbuchstaben enthalten.

Die API erhält weiterhin `S3_ACCESS_KEY` und `S3_SECRET_KEY`; nur die Namen im
Secret ändern sich. Zugangsdaten gehören nicht in die Helm-Values.
Nach einer Rotation Secret aktualisieren und die API-Pods
neu starten; laufende Prozesse lesen Secret-ENV nicht automatisch neu:

```bash
kubectl rollout restart deployment/ddb-api-v3-api-service -n ddb-api-v3
```

Die internen Redis-/Sentinel-Zugangsdaten erzeugt und verwaltet Helm.
Diese Secrets sind Teil des Release-Zustands. Kein gemeinsames S3-/Redis-Passwort
verwenden. Für private Images ein Registry-Pull-Secret über `images.pullSecrets`
referenzieren.

## Values und Installation

Das [Values-Beispiel](../charts/ddb-api-v3/README.md#installieren) setzt Speicher,
öffentlichen Host, TLS, RDF-Origin und optional OAuth2/OIDC. Ohne Issuer und
Audience sind Schreibzugriffe gesperrt. Beide gemeinsam setzen; eine JWKS-URL
ist optional. [Sicherheitskonfiguration und Keycloak](security.md).

`appProfile` setzt `APP_PROFILE` für beide Anwendungen. `prod` verwendet gemeinsame
Redis-Quoten und JWT-Schreibschutz; `dev` verwendet lokale Quoten. Ein Dev-Token
kann aus einem vorab angelegten Secret mit Schlüssel `DDB_DEV_WRITE_TOKEN` kommen:

```yaml
appProfile: dev
auth:
  devWriteTokenSecret: ddb-api-v3-dev-writer
```

Das Token gemäß [Security-Anleitung](security.md#lokal-ohne-identity-provider)
erzeugen und ausschließlich im Secret speichern. API und Gateway lesen denselben
Wert. Diese Option ist nur mit `dev` und leeren JWT-Einstellungen zulässig;
sonst lehnt die Schema-Validierung die Konfiguration ab.

`apiService.maxDocumentSize` begrenzt den entpackten Upload, `gateway.rateLimit`
konfiguriert Kapazität, Nachfüllmenge, Intervall und Kosten einer Anfrage.
`apiService.forwardHeadersStrategy` und `gateway.forwardHeadersStrategy` setzen
jeweils `SERVER_FORWARD_HEADERS_STRATEGY`; hinter vertrauenswürdigen Proxies
bewusst wählen. [Alle ENV-Zuordnungen](../charts/ddb-api-v3/values.schema.json).
Das Schema liefert auch die Feldbeschreibungen für das native OpenShift-Formular;
dieselben Werte lassen sich weiterhin direkt in der YAML-Ansicht bearbeiten.

`ingress.host` und `tlsSecretName` bestimmen den HTTP-Einstiegspunkt;
ohne Secret-Namen verwendet der Ingress das Standardzertifikat seines Controllers.
Der aktivierte Ingress enthält immer einen TLS-Host; HTTP-zu-HTTPS-Weiterleitung
am jeweiligen Controller sicherstellen.
`apiService.baseUrl` bestimmt Ressourcen-IRIs. Hinter einem TLS-Ingress den
passenden öffentlichen Origin setzen. `auto` folgt der HTTP-Anfrage und entfernt
`/3`; bestehende RDF-IRIs ändert das nicht. Forwarded-Header standardmäßig nicht
vertrauen. Abweichende Proxy-Konfigurationen gemäß den [Logging-Regeln](logging.md)
bewusst vornehmen.

Für einen lokalen Chart die fixierte Redis-Abhängigkeit auflösen:

```bash
helm repo add groundhog2k https://groundhog2k.github.io/helm-charts/
helm dependency build charts/ddb-api-v3
helm lint charts/ddb-api-v3 --values values-site.yaml
helm template ddb-api-v3 charts/ddb-api-v3 --namespace ddb-api-v3 \
  --values values-site.yaml
helm install ddb-api-v3 ./charts/ddb-api-v3 \
  --namespace ddb-api-v3 --values values-site.yaml --wait --timeout 10m
```

`Chart.lock` fixiert die Abhängigkeit; Archive werden beim Build geladen und
nicht versioniert. Alle Images werden aus Registries bezogen. Beim Betrieb
gezielt Versionen auswählen; [Veröffentlichung](operations.md#container-und-releases).
Seltene Anwendungsoptionen lassen sich über `apiService.extraEnv` und
`gateway.extraEnv` überschreiben, beispielsweise native JVM-Optionen.

## Skalierung und Rollouts

API und Gateway skalieren unabhängig mit `replicaCount` oder dem jeweiligen
CPU-HPA. Standard sind je ein Pod, API/Gateway/Redis zusammen 175m CPU- und
416Mi RAM-Requests bei 896Mi RAM-Limits. Die API bearbeitet vier Anfragen parallel,
acht weitere warten höchstens eine Sekunde; S3-Pool: acht Verbindungen,
Gateway-Pool: 32. Diese Werte sind für kleine Last gedacht und lassen sich
unabhängig erhöhen. Die kleine Installation ist nicht hochverfügbar.

Das Produktionsprofil erhöht API/Gateway auf drei/zwei Replikate, Ressourcen,
Parallelität und Pools; Redis erhält drei Nodes mit Sentinel. So installieren:

```bash
helm install ddb-api-v3 ./charts/ddb-api-v3 -n ddb-api-v3 --create-namespace \
  -f charts/ddb-api-v3/values-production.yaml -f values-site.yaml \
  --wait --timeout 10m
```

HPA ist auch dort zunächst aus. Zum Aktivieren `apiService.autoscaling.enabled`
und `gateway.autoscaling.enabled` setzen. HPA benötigt einen Metrics Server,
positive CPU-Requests und passende
Min-/Max-Replikagrenzen. Die Zielauslastung bezieht sich auf den CPU-Request,
nicht auf das Limit. Vertikale Kapazität wird mit `resources` konfiguriert:

```yaml
apiService:
  resources:
    requests: {cpu: "1", memory: 1Gi}
    limits: {cpu: "2", memory: 2Gi, ephemeral-storage: 64Mi}
  concurrency:
    maxRequests: 32
    maxQueuedRequests: 64
    queueTimeout: 1s
  autoscaling:
    enabled: true
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilizationPercentage: 70
s3:
  maxConnections: 50
gateway:
  maxConnections: 256
```

Die JVM verwendet standardmäßig bis zu 60 % des RAM-Limits als Heap und beendet
sich bei OOM. Der Rest bleibt für native Puffer, Metaspace und Threads. Ein
festes `-Xmx` ist nicht eingebaut. Ressourcenänderungen werden mit neuen Pods
wirksam; `extraEnv.JAVA_TOOL_OPTIONS` kann die Vorgabe ersetzen.

RDF-Arbeit, Warteschlange und S3-Pool gemeinsam mit CPU/RAM und realen
Dokumentgrößen abstimmen. Mehr RAM erhöht `concurrency.maxRequests` nicht von
selbst. Volle API-Kapazität ergibt `503`. Der Gateway-Pool ist auf
`maxConnections` begrenzt; maximal doppelt so viele Anfragen warten eine Sekunde.
Ein voller Pool ergibt derzeit die native Gateway-Antwort `500`. Mehr API-Pods
erhöhen auch die Gesamtzahl möglicher S3-Verbindungen. Keep-Alive-Verbindungen
haben eine begrenzte Lebensdauer für die Verteilung auf neue Replikate.

Kein VPA-Controller ist erforderlich. Bei zusätzlichem VPA automatische
CPU-Änderungen nicht ohne Abstimmung gegen CPU-HPA regeln lassen. Bei einer
Mindest-Replikazahl von eins ersetzen Rollouts den alten Pod ohne zusätzlichen
Surge-Pod; dabei gibt es eine Unterbrechung. Ab zwei Mindest-Replikaten startet
zuerst ein zusätzlicher Pod und muss bereit sein, bevor ein alter stoppt.
Beim Stoppen bleiben fünf Sekunden für
Endpoint-Aktualisierung, anschließend bis zu 20 Sekunden für laufende Requests
innerhalb der 30-sekündigen Grace-Period. Disruption-Budgets erlauben höchstens
einen nicht verfügbaren Pod je Deployment; ein einzelnes Replikat bietet keine
Verfügbarkeit während eines Node-Drains. Nur `/tmp` ist beschreibbar und begrenzt,
standardmäßig auf 64 MiB; dort liegt kein fachlicher Zustand.

## Redis, Persistenz und OpenShift

Der Redis-Subchart betreibt standardmäßig eine einzelne Instanz ohne Sentinel,
mit 32 MiB `maxmemory` und einem 1-GiB-PVC. Auch mehrere Gateway-Replikate können
dieselbe Redis-Instanz für gemeinsame Quoten verwenden. RDF liegt ausschließlich
im S3-Speicher.

`redis.useDeploymentWhenNonHA` wählt den Controller für diese Einzelinstanz:

| Wert | Verhalten |
| --- | --- |
| `false` (Default) | StatefulSet mit fester Pod-Identität und eigenem PVC je Pod. |
| `true` | Deployment mit einem Pod und eigenem PVC; `Recreate` stoppt den alten Pod vor dem neuen. |

Beide Varianten verwenden persistenten Speicher und dieselben ACLs und Probes.
Bei aktiviertem HA läuft Redis immer als StatefulSet; der Schalter hat dann
keine Wirkung.

`redis.haMode.enabled: true` aktiviert einen Primary, zwei Replicas und je Pod
einen Sentinel. Das Produktionsprofil setzt außerdem harte Anti-Affinität und
benötigt drei passende Worker-Nodes. Das Quorum ist zwei. Die Gateways wechseln
automatisch von der direkten Redis-Adresse zu Sentinel-Discovery.
HA verbessert die Verfügbarkeit, nicht den Schreibdurchsatz.

`redis.podDisruptionBudget.maxUnavailable: 1` erlaubt bei freiwilligen
Unterbrechungen höchstens einen nicht verfügbaren Redis-Pod. Ein einzelner
Redis-Pod ist dabei vorübergehend nicht erreichbar. Der Chart verwendet nur
`maxUnavailable`; `minAvailable` darf nicht zusätzlich gesetzt werden.

HA-Werte: Ausfallerkennung 5 Sekunden, Failover-Timeout 60 Sekunden.
AOF mit `appendfsync everysec` und `noeviction` gelten in beiden Modi;
das Produktionsprofil erhöht `maxmemory` auf 256 MiB. Ressourcen,
Sentinel und Speicher lassen sich über `redis.resources`, `redis.sentinelResources`,
`redis.haMode` und `redis.storage` anpassen. Die Redis-NetworkPolicy erlaubt nur
Redis- und Gateway-Pods desselben Namespaces den Zugriff auf Redis/Sentinel.
Der Gateway-Benutzer besitzt keine administrativen Redis-Befehle; der
Statusabruf bleibt erlaubt.

Redis-Kennwörter beim Rotieren zusammen mit einem abgestimmten Redis-/Gateway-
Neustart ändern; laufende Prozesse lesen ACLs und ENV nicht neu.

Verwaltete Redis-Secrets und PVCs bleiben nach `helm uninstall` erhalten:
beim Deployment über `redis.storage.keepPvc: true`, beim StatefulSet über
`whenDeleted/whenScaled: Retain`; Secrets tragen `helm.sh/resource-policy: keep`. Für eine
Wiederinstallation denselben Release-Namen und Namespace verwenden. Die
endgültige Daten-/Secret-Löschung ist ein eigener, bewusster Betriebsschritt.

Die DDB-Container sind nicht privilegiert, verwerfen Linux-Capabilities und
verwenden schreibgeschützte Root-Dateisysteme. DDB- und Redis-Pods mounten keine
Service-Account-Tokens. Feste UID/GID/fsGroup und eine feste IngressClass werden
nicht vorgegeben; OpenShift kann Namespace-IDs und den Router zuweisen.
Den Redis-ServiceAccount legt das DDB-Chart automatisch ohne API-Rechte an.
`redis.serviceAccount.create: false` deaktiviert nur die doppelte Erstellung
durch den Unterchart. Der Account ist die Kubernetes-Identität der Pods;
Redis-Logins sind davon unabhängig. Diese internen Einstellungen werden im
OpenShift-Formular nicht angeboten.
HTTPS und globale Anfrage-/Verbindungsgrenzen am öffentlichen Ingress erzwingen.

## Zustand prüfen

```bash
kubectl get pods,services,ingress -n ddb-api-v3
kubectl rollout status deployment/ddb-api-v3-api-service -n ddb-api-v3
kubectl rollout status deployment/ddb-api-v3-gateway -n ddb-api-v3
kubectl rollout status statefulset/ddb-api-v3-redis -n ddb-api-v3
```

Sentinel-Discovery nur bei aktiviertem HA:

```bash
kubectl exec -n ddb-api-v3 ddb-api-v3-redis-0 -c redis-sentinel -- \
  sh -c 'REDISCLI_AUTH="$(sed -n "s/^sentinel sentinel-pass //p" /data/conf/sentinel.conf)" \
  redis-cli --user sentinel-admin -p 26379 SENTINEL get-master-addr-by-name ddb-api-v3'
```

Unter OpenShift dieselben Befehle mit `oc` verwenden. Readiness prüft S3
beziehungsweise Redis; Liveness bleibt von deren Ausfällen unabhängig. Logs
stehen auf stdout; [Auswertung und Aufbewahrung](logging.md) übernimmt die
vorhandene Cluster-Log-Pipeline.

Die Chart-Veröffentlichung prüft Standalone- und HA-ACLs aus den tatsächlich
gerenderten Secrets mit temporären Docker-Containern. Der Smoke-Test erlaubt Datenzugriff und
Discovery, verweigert Administration sowie die Anmeldung als Peer mit einem
Client-Passwort. Lokal benötigt diese Prüfung Docker; reine Render-Prüfungen
ersetzen den Lauf gegen Redis nicht.
