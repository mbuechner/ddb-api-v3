# Betrieb und Entwicklung

## Lokaler Start

Die Kurzbefehle stehen im [Projekt-README](../README.md). Die Java-Anwendungen
benötigen Java 25 und Maven; für den vollständigen Compose-Start genügt Docker
mit Compose. Der lokale Speichercontainer ist eine Entwicklungsumgebung.

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

Standardports sind 8080 für die direkt gestartete API, 8081 für das Gateway
und 8333 für den lokalen S3-Endpunkt. Compose veröffentlicht nur Gateway und
lokalen Speicher auf Loopback; die API bleibt im Compose-Netz. Das Volume
`seaweedfs-data` erhält die Daten über Containerwechsel hinweg. `docker compose
down -v` entfernt es und damit lokale Daten; für normale Neustarts kein `-v` verwenden.
Die öffentlichen S3-Beispielzugänge aus `.env.example` nur lokal einsetzen.

## S3-Servicekonto

Endpunkt, Bucket und Region konfigurieren; Standardregion ist `eu-central-1`.
Der Bucket muss in Produktion vorab existieren. Das Konto benötigt Objekt-Lese-
und Schreibrechte sowie Zugriff für `HeadBucket`; Bucket-Anlegerechte sind bei
`S3_CREATE_BUCKET=false` nicht erforderlich. Bedingte PUTs mit `If-Match` und
`If-None-Match` müssen vom Speicher atomar unterstützt werden.

Die Bezeichnungen aus Kontounterlagen werden folgendermaßen zugeordnet:

| Anbieter | API-Konfiguration |
| --- | --- |
| `AWS_ENDPOINT_URL` | `S3_ENDPOINT` |
| `AWS_ACCESS_KEY_ID` | `S3_ACCESS_KEY` |
| `AWS_SECRET_ACCESS_KEY` | `S3_SECRET_KEY` |

```dotenv
S3_ENDPOINT=https://s3.storage.example.org
S3_BUCKET=ddb-records
S3_REGION=eu-central-1
S3_ACCESS_KEY=<Access-Key-des-Servicekontos>
S3_SECRET_KEY=<Secret-Key-des-Servicekontos>
S3_CREATE_BUCKET=false
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
Ein Speicherausfall darf die Liveness nicht zu einer Neustartschleife machen.
Für lokale Readiness-Probleme zuerst S3-Endpunkt, Konto, Bucket und Profil prüfen.
Das Gateway bietet zusätzlich `/actuator/metrics` und `/actuator/prometheus`;
diese Betriebsendpunkte nur gezielt für Monitoring erreichbar machen.

Beide Anwendungen schreiben strukturierte JSON-Logs auf stdout. Request-IDs
verbinden HTTP-Antworten mit Log-Ereignissen. Nutzungszahlen aus Gateway-Logs
berechnen, API-Logs getrennt für die Backend-Performance auswerten:
[Logging und Statistiken](logging.md). Daten und Log-Backend werden nicht
im API-Prozess zwischengespeichert oder betrieben.

## Container und Releases

Die Module haben eigene mehrstufige Dockerfiles. Sie bauen mit Maven und
enthalten im Runtime-Image nur Java 25 und das jeweilige Anwendungs-JAR:

```bash
docker build --pull -t registry.example.org/ddb/api-service:1.0.0 api-service
docker build --pull -t registry.example.org/ddb/api-gateway:1.0.0 api-gateway
```

Die Workflows [container-images.yaml](../.github/workflows/container-images.yaml)
und [helm-pages.yaml](../.github/workflows/helm-pages.yaml) veröffentlichen getrennte
GHCR-Images und den Chart. Pull Requests werden gebaut, aber nicht veröffentlicht.
Pushes auf `main` erhalten unter anderem `latest` und `sha-<Commit>`; Git-Tags
`v1.2.3` erzeugen zusätzlich Versionstags. Veröffentlichte Chart-Pakete referenzieren
die beiden Images desselben Commits, nachdem deren Manifeste geprüft wurden.

Die Pakete sind `ghcr.io/mbuechner/ddb-api-v3-service` und
`ghcr.io/mbuechner/ddb-api-v3-gateway`. Produktionsversionen bewusst auswählen.
Private Registries benötigen ein Pull-Secret. Images sind nicht im Chart enthalten.
Die Chart-Patchversion berechnet CI als `Laufnummer * 1000 + Versuch`; Major/Minor
stammen aus `Chart.yaml`. Das Pages-Repository enthält den zuletzt veröffentlichten Stand.

`mvn clean verify` prüft beide Module samt OpenAPI. Docker-basierte Integrationstests
benötigen Docker; fehlende Infrastruktur kann zu ausgewiesenen Test-Skips führen.
Für die CI- oder Release-Freigabe diese Skips bewusst bewerten.
