# Betrieb und Entwicklung

## Lokaler Start

Die Kurzbefehle stehen im [Projekt-README](../README.md). Die Java-Anwendungen
benötigen Java 25 und Maven; für den vollständigen Compose-Start genügt Docker
mit Compose. Der lokale Speichercontainer ist eine Entwicklungsumgebung.

Im Projektverzeichnis startet `mvn spring-boot:run` API und Gateway gemeinsam;
S3 muss bereits erreichbar sein. `Strg+C` beendet beide Anwendungen.
Für Einzelstarts weiterhin `mvn -pl api-service spring-boot:run` beziehungsweise
`mvn -pl api-gateway spring-boot:run` verwenden, auch direkt im Modulverzeichnis
ist `mvn spring-boot:run` möglich.

`.mvn/maven.config` setzt zwei Build-Threads, damit die beiden blockierenden
Startziele parallel laufen. Das gilt auch für normale Builds; bei Bedarf mit
`mvn -T 1 verify` seriell bauen. Das Root-POM überspringt den Boot-Start nur für
das Aggregatorprojekt, das selbst keine Anwendung enthält.

| Einstellung | `dev` | `prod` |
| --- | --- | --- |
| RDF-Speicher | S3-kompatibler Objektspeicher | S3-kompatibler Objektspeicher |
| Gateway-Limits | Prozesslokale Bucket4j-/Caffeine-Buckets | Gemeinsame Buckets in Redis |
| Schreiben | Optionales Dev-Token oder JWT | Ausschließlich geprüftes JWT |

`APP_PROFILE` gilt für beide Anwendungen. Mehrere produktive Gateways brauchen
`prod`, damit ihr Rate-Limit-Zustand gemeinsam ist. Beide Prozesse lesen die
private `.env` aus dem Repository beziehungsweise dem übergeordneten
Modulverzeichnis. Gesetzte Prozessvariablen haben Vorrang. Änderungen an ENV,
Secrets oder Profilen werden erst beim Neustart der betreffenden Prozesse wirksam.

Die ENV-Dateien trennen gemeinsame Werte von `DDB_API_*` (API, RDF und S3)
und `GATEWAY_*` (Routing, Quoten und Redis). Logging und Forwarding sind je
Anwendung getrennt einstellbar. Profil, Dev-Token und OAuth-Vertrauen bleiben
gemeinsam, damit beide Komponenten dieselben Schreibzugriffe prüfen.

Vorhandene Shell- und Deployment-Konfigurationen auf die neuen Namen umstellen:

| Bisher | Jetzt |
| --- | --- |
| `SERVER_PORT` | `DDB_API_PORT` |
| `S3_*` | `DDB_API_S3_*` |
| `API_SERVICE_URI` | `GATEWAY_API_SERVICE_URI` |
| `REDIS_*`, `RATE_LIMIT_*` | `GATEWAY_REDIS_*`, `GATEWAY_RATE_LIMIT_*` |
| Geteilte Logging-/Forwarding-Variablen | Jeweils `DDB_API_*` oder `GATEWAY_*`; siehe `.env.example` |

Native Spring-Boot-Sentinel-Variablen behalten ihre `SPRING_DATA_REDIS_SENTINEL_*`-
Namen. Im Helm-Chart bleiben auch die Datenschlüssel vorhandener Secrets erhalten;
die Templates ordnen sie den neuen Container-ENV-Namen zu.

Standardports sind 8080 für die direkt gestartete API, 8081 für das Gateway
und 8333 für den lokalen S3-Endpunkt. Der Anwender wählt freie, unterschiedliche
Werte für `DDB_API_PORT` und `GATEWAY_PORT`. Der Gateway-Port ist innerhalb und
außerhalb des Compose-Containers identisch. Beim direkten Java-/Maven-Start
muss `GATEWAY_API_SERVICE_URI` zum gewählten `DDB_API_PORT` passen; ohne festen
Override folgt das lokale Backend-Ziel automatisch dem API-Port.
Compose veröffentlicht nur Gateway und
lokalen Speicher auf Loopback; die API bleibt im Compose-Netz. Das Volume
`seaweedfs-data` erhält die Daten über Containerwechsel hinweg. `docker compose
down -v` entfernt es und damit lokale Daten; für normale Neustarts kein `-v` verwenden.
Die öffentlichen S3-Beispielzugänge aus `.env.example` nur lokal einsetzen.

## S3-Servicekonto

Endpunkt, Bucket und Region konfigurieren; Standardregion ist `eu-central-1`.
Der Bucket muss in Produktion vorab existieren. Das Konto benötigt Objekt-Lese-
und Schreibrechte sowie Zugriff für `HeadBucket`; Bucket-Anlegerechte sind bei
`DDB_API_S3_CREATE_BUCKET=false` nicht erforderlich. Bedingte PUTs mit `If-Match` und
`If-None-Match` müssen vom Speicher atomar unterstützt werden.

Die Bezeichnungen aus Kontounterlagen werden folgendermaßen zugeordnet:

| Anbieter | API-Konfiguration |
| --- | --- |
| `AWS_ENDPOINT_URL` | `DDB_API_S3_ENDPOINT` |
| `AWS_ACCESS_KEY_ID` | `DDB_API_S3_ACCESS_KEY` |
| `AWS_SECRET_ACCESS_KEY` | `DDB_API_S3_SECRET_KEY` |

```dotenv
DDB_API_S3_ENDPOINT=https://s3.storage.example.org
DDB_API_S3_BUCKET=ddb-records
DDB_API_S3_REGION=eu-central-1
DDB_API_S3_ACCESS_KEY=<Access-Key-des-Servicekontos>
DDB_API_S3_SECRET_KEY=<Secret-Key-des-Servicekontos>
DDB_API_S3_CREATE_BUCKET=false
```

Der Endpunkt ist ein HTTP(S)-Origin ohne Bucket-Pfad, Query oder Zugangsdaten.
Das Beispiel gilt für Maven/JAR; Compose verwendet seinen lokalen Speicher.
Im Cluster stehen diese Werte in Values und einem vorhandenen Secret, siehe
[Bereitstellung](deployment.md#speicher-und-secrets).

Sind beide expliziten S3-Zugangsdaten leer, verwendet das SDK seine native
Credential-Provider-Kette. Bei expliziten Angaben müssen Access-Key und
Secret-Key zusammenpassen. Die vollständige Variablenreferenz, einschließlich
Timeouts und Verbindungspools, steht in [.env.example](../.env.example).

## Health und Diagnose

| Endpunkt | Verwendung |
| --- | --- |
| `/actuator/health/liveness` | Läuft der Prozess? |
| `/actuator/health/readiness` | Kann die Instanz Anfragen bedienen? |
| `/actuator/health` | Zusammengefasster Gesundheitszustand |

Die API-Readiness prüft den S3-Bucket, die produktive Gateway-Readiness Redis.
Die API nennt unter `components` die betroffene Prüfung. Bei `s3Storage: DOWN`
enthält `details` nur die Fehlerklasse (`error`) und bei einer S3-Antwort den
HTTP-Status (`httpStatus`), keine Exception-Texte oder Zugangsdaten.
`HeadBucket` scheitert dann für den konfigurierten Bucket: Endpoint/DNS/TLS,
S3-Zugangsdaten, Bucket-Berechtigungen und das Zwei-Sekunden-Zeitlimit prüfen.
Ein Speicherausfall darf die Liveness nicht zu einer Neustartschleife machen.
Für lokale Readiness-Probleme zuerst S3-Endpunkt, Konto, Bucket und Profil prüfen.
Das Gateway bietet zusätzlich `/actuator/metrics` und `/actuator/prometheus`;
diese Betriebsendpunkte nur gezielt für Monitoring erreichbar machen.

Beide Anwendungen schreiben strukturierte JSON-Logs auf stdout. Request-IDs
verbinden HTTP-Antworten mit Log-Ereignissen. Nutzungszahlen aus Gateway-Logs
berechnen, API-Logs getrennt für die Backend-Performance auswerten:
[Logging und Statistiken](logging.md). Daten und Log-Backend werden nicht
im API-Prozess zwischengespeichert oder betrieben.
Bei zusätzlichen Antwortzeiten zuerst die korrelierten Teilzeiten ansehen:
[OpenShift-Latenzdiagnose](latency-diagnostics.md) trennt öffentlichen Netzweg,
Gateway/Redis, API-Warteschlange, S3 und RDF-Ausgabe.

### Gateway-Startmeldungen

`Picked up JAVA_TOOL_OPTIONS` ist eine JVM-Startinformation. Das Gateway-Image
erlaubt Nettys nativen Classpath-Zugriff mit `--enable-native-access=ALL-UNNAMED`.
Beim Ersetzen von `JAVA_TOOL_OPTIONS` diese Option zusammen mit den gewünschten
Heap-Einstellungen beibehalten.

Spring Cloud kann beim Start `not eligible for getting processed by all
BeanPostProcessors` für früh erzeugte Framework-Konfigurationen melden.
`HV000271` betrifft veraltete `@Valid`-Annotationen auf Gateway-Listen;
deren Kaskadenvalidierung ist weiterhin aktiv. Diese Framework-Meldungen
verhindern den Start im geprüften Setup nicht. Sie bleiben sichtbar.

## Container und Releases

Die Module haben eigene mehrstufige Dockerfiles. Die Build-Stufe verwendet
`maven:3-amazoncorretto-25`, die Laufzeit-Stufe `amazoncorretto:25`.

```bash
docker build --pull -t registry.example.org/ddb/api-service:1.0.0 api-service
docker build --pull -t registry.example.org/ddb/api-gateway:1.0.0 api-gateway
```

Die Workflows [container-images.yaml](../.github/workflows/container-images.yaml)
und [helm-pages.yaml](../.github/workflows/helm-pages.yaml) veröffentlichen getrennte
GHCR-Images und den Chart. Pull Requests werden gebaut, aber nicht veröffentlicht.
Pushes auf `main` erhalten unter anderem `latest` und `sha-<Commit>`; Git-Tags
`v1.2.3` erzeugen zusätzlich Versionstags. Sobald beide Image-Builds des
aktuellen Standardbranches erfolgreich sind, versieht CI die Images aus diesem
Lauf mit dem Tag `rolling`. Vor der Chart-Veröffentlichung prüft CI die
Commit- und Rolling-Tags.

Für ein aktuelleres Corretto-Basisimage den Workflow unter **Actions → Container
images → Run workflow** auf `main` mit aktiviertem
`rebuild_without_cache` starten. GitHub Actions prüft Docker Hub auf aktuelle
Basis-Images und baut beide Anwendungen standardmäßig ohne Docker-Layer-Cache.
Das funktioniert auch mit demselben Commit. Der dabei aktualisierte
`sha-<Commit>`-Tag identifiziert den Quellstand. Der Image-Digest identifiziert
den konkreten Build.

Die Pakete sind `ghcr.io/mbuechner/ddb-api-v3-service` und
`ghcr.io/mbuechner/ddb-api-v3-gateway`. Der Chart verwendet für beide Anwendungen
standardmäßig `rolling`. OpenShift prüft die Tags im geplanten Importintervall.
Ein neuer Digest löst über Image-Change-Trigger den jeweiligen Workload-Rollout
aus. API und Gateway warten jeweils auf einen verfügbaren neuen Pod, bevor sie
den bisherigen beenden. Der Cluster benötigt dafür Kapazität für die zusätzlichen
Pods. Die lokalen Stream-Tags heißen jeweils `latest`. Die externen Quell-Tags
lassen sich über die Image-Values konfigurieren, beispielsweise auf einen
unveränderlichen Release-Tag aus der eigenen Registry.

Redis importiert standardmäßig `8-alpine`. Die Trigger erfassen auch seine
Init-Container sowie aktivierte Sentinel- und Exporter-Container.
Redis verwendet explizite, pro Namespace eindeutige Streamnamen und Triggerlisten
in Values. HA-/Exporter-Wechsel mit den passenden [Chart-Overlays](deployment.md#imagestreams-und-rollouts)
vornehmen, damit keine Container-Pfade fehlen oder auf inaktive Container zeigen.
Private Registries benötigen passende Secrets für Image-Import und Pod-Pulls.
Container-Binaries sind nicht im Chart enthalten.
Die Chart-Patchversion berechnet CI als `Laufnummer * 1000 + Versuch`; Major/Minor
stammen aus `Chart.yaml`. `appVersion` dokumentiert den Chart-Quellcommit und pinnt
die Registry-Tags nicht. Das Pages-Repository enthält den zuletzt veröffentlichten Stand.

`mvn clean verify` prüft beide Module samt OpenAPI. Docker-basierte Integrationstests
benötigen Docker; fehlende Infrastruktur kann zu ausgewiesenen Test-Skips führen.
Für die CI- oder Release-Freigabe diese Skips bewusst bewerten.
