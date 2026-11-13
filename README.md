# DDB API v3

RDF rein. Jena prüft. Jelly hält die Triples kompakt zusammen; SeaweedFS speichert sie über S3.
Spring MVC übernimmt HTTP; das Spring Cloud Gateway begrenzt Zugriffe lokal
im Prozess und produktiv verteilt über Redis.

Der Vertrag dieser Anwendung liegt in
[openapi.yaml](api-service/src/main/openapi/openapi.yaml). Maven validiert ihn;
Swagger UI liest dieselbe Datei unter `/openapi/openapi.yaml`. Die drei
HTTP-Methoden sind direkt implementiert, Fehler verwenden Springs `ProblemDetail`.

Das separate Projekt [apiv3-spec](../apiv3-spec/README.md) enthält weitere
API-Dokumentation. Änderungen zwischen beiden Projekten müssen mit diesem
vereinfachten RDF-Vertrag abgeglichen werden.

## Package-Aufteilung

Unter `de.ddb.apiv3` folgen die Endpunkt-Packages den URL-Namen. RDF und
Speicherung sind gemeinsame Bausteine:

| Package | Aufgabe |
| --- | --- |
| `records` | `RecordsController`, `RecordUploadService` und Konflikte beim Anlegen eines Records |
| `items` | Mit `package-info.java` vorbereitet; der Endpunkt folgt später |
| `rdf` | `RdfCodec` und `InvalidRdfException`: RDF prüfen und Jelly umwandeln |
| `storage` | `RdfDocumentStore` und `StorageException`: RDF-Dokumente nach ID und Dokumentname speichern |
| `storage.s3` | `S3RdfDocumentStore`, S3-Konfiguration und Verbindungsparameter |
| `config` | `ApiProperties`: gemeinsame Basis-URL für die Named Graphs |

Das Endpunkt-Package wählt Objektname und Graph-URI. Der gemeinsame Speicher
bleibt dadurch auch für künftige Items und Organizations nutzbar.

## Implementierungsstand

Die Records-API empfängt RDF direkt im Request-Body. `Content-Type` wählt einen
von Apache Jena unterstützten Parser, etwa Turtle, RDF/XML, JSON-LD, N-Triples,
N-Quads, TriG oder Jelly. Vor jedem Speichern prüft Jena das vollständige RDF;
ungültiges RDF wird mit `422`, ein unbekanntes Eingabeformat mit `415`
abgewiesen, bevor ein S3-Schreibzugriff erfolgt. Alle Triples landen in einem
Named Graph mit dem Namen `{Basis-URL}/records/{recordId}`. Standard ist
`https://api.deutsche-digitale-bibliothek.de/records/{recordId}`.
Eingehende Graphnamen werden durch diesen Namen ersetzt; mehrere Graphen werden
vereinigt. JSON-LD-Kontexte müssen im Request enthalten sein; externe Kontexte
werden nicht über das Netzwerk geladen. Die Stelle für eine spätere
SHACL-Validierung ist mit `TODO: SHACL ergänzen.` markiert.

Die Basis-URL steht in `ddb.api.base-url` und lässt sich über die
Umgebungsvariable `DDB_API_BASE_URL` setzen, zum Beispiel
`DDB_API_BASE_URL=https://api.example.org`. Erlaubt ist ein HTTP(S)-Origin mit
optionalem Port und abschließendem Slash. Der Standard lautet
`https://api.deutsche-digitale-bibliothek.de`. Request-Host und Forwarded-Header
bestimmen den Graphnamen nicht. Eine geänderte Basis gilt für neue `POST`- und
`PUT`-Schreibzugriffe; `GET` erhält die bereits gespeicherten Graphnamen.

| Methode | Endpunkt | Verhalten |
| --- | --- | --- |
| `GET` | `/3/records/{recordId}` | Jelly lesen und gemäß `Accept` ausgeben; `200`, bei unbekannter ID `404` |
| `POST` | `/3/records/{recordId}` | Neuen RDF-Datensatz anlegen; `201`, bei vorhandenem Schlüssel `409` |
| `PUT` | `/3/records/{recordId}` | RDF-Datensatz mit passendem `If-Match` ersetzen; `204`, bei veralteter Bedingung `412` |

Die DDB-ID besteht aus 32 Zeichen des Alphabets `A-Z` und `2-7`. Erfolgreiche
`POST`-Zugriffe liefern den S3-ETag im HTTP-Header `ETag`; `PUT` liefert ihn als
`X-Record-ETag`, weil die Eingabe beim Speichern umgewandelt wird. Ein `PUT` ohne
`If-Match` liefert `428`. `GET` wählt über `Accept` einen installierten Jena-Writer;
ohne Vorgabe liefert es TriG. Dataset-Formate erhalten den kanonischen Graphnamen,
Graph-Formate wie Turtle oder RDF/XML liefern dessen vollständigen Inhalt ohne
Graphnamen. Ein nicht unterstütztes oder für diese RDF-Daten ungeeignetes
Ausgabeformat liefert `406`.
`GET` liefert einen schwachen `ETag` und den starken Speicher-ETag als
`X-Record-ETag`; für `PUT If-Match` diesen starken Wert verwenden.
`GET If-None-Match` kann bei unverändertem Datensatz mit `304` antworten.
Die Anwendung erzeugt kein internes Metadatenmodell.
Der gespeicherte Objektname ist Teil der jeweiligen
API-Methode: Die Record-Methode schreibt fest unter `{recordId}/record`.

Beispiel für eine Neuanlage:

```bash
curl -i -X POST \
  http://localhost:8081/3/records/J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3 \
  -H 'Content-Type: application/rdf+xml' \
  --data-binary @record.rdf
```

Zum Ersetzen denselben Endpunkt mit `PUT` und dem zuvor empfangenen ETag
aufrufen:

```bash
curl -i -X PUT \
  http://localhost:8081/3/records/J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3 \
  -H 'Content-Type: application/rdf+xml' \
  -H 'If-Match: "ETag-aus-der-vorherigen-Antwort"' \
  --data-binary @record.rdf
```

Zum Lesen bestimmt `Accept` die Ausgabe, unabhängig vom ursprünglichen Uploadformat:

```bash
curl -i \
  http://localhost:8081/3/records/J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3 \
  -H 'Accept: text/turtle'
```

Die OpenAPI-Spezifikation deklariert OAuth2-Scopes, die Anwendung enthält aber
noch keinen Resource Server. Schreibzugriffe dürfen deshalb nicht öffentlich
bereitgestellt werden, bevor Authentifizierung und Scope-Prüfung ergänzt wurden.
Das Produktions-Gateway arbeitet ohne authentifizierten Principal bewusst
fail-closed und antwortet auf `/3/**` mit `403 Forbidden`.

## Lokal starten

Voraussetzung sind Java 25, Maven und ein erreichbarer SeaweedFS-S3-Endpunkt.
Die lokale Umgebung startet einen persistenten SeaweedFS-Container:

```bash
cp .env.example .env
docker compose up -d seaweedfs
```

Danach startet der API-Service mit dem Standardprofil `dev`:

```bash
mvn -pl api-service spring-boot:run
```

Swagger UI: <http://localhost:8080/swagger-ui.html>

Das Gateway kann in einem zweiten Terminal ohne Redis gestartet werden. Im
Standardprofil `dev` liegen die Rate-Limit-Buckets im Arbeitsspeicher:

```bash
mvn -pl api-gateway spring-boot:run
```

Der öffentliche Einstiegspunkt ist dann <http://localhost:8081/>. Beide
Anwendungen verwenden dabei dieselbe Entwicklungskonfiguration ohne Redis.

SeaweedFS bietet S3 lokal unter <http://127.0.0.1:8333> an. Der Bucket heißt
standardmäßig `ddb-records`. Die öffentlichen Beispiel-Zugangsdaten
`ddb-dev` / `ddb-dev-secret` gelten ausschließlich für die lokale Entwicklung.
Endpunkt, Bucket, Region, Zugangsdaten und Timeouts lassen sich über die in
[.env.example](.env.example) dokumentierten `S3_*`-Variablen ändern. Bei einer
eigenen SeaweedFS-Installation muss die dort konfigurierte Identität zu diesen
Zugangsdaten passen.

Für API-Service und Gateway gemeinsam kann Docker verwendet werden:

```bash
docker compose up --build
```

Swagger UI ist dann unter <http://localhost:8081/swagger-ui.html> erreichbar.
Das benannte Volume `seaweedfs-data` erhält die Record-Daten auch bei einem
Neustart oder erneuten Erstellen der Container. Compose verwendet die fixierte
Version `chrislusf/seaweedfs:4.53` im Mini-Modus; dieser einzelne Storage-Container
ist für Entwicklung vorgesehen.
Das Gateway ist der öffentliche Einstiegspunkt und erlaubt nicht-credentialed
CORS-Zugriffe von allen Origins; direkte Service-Aufrufe auf Port 8080 sind intern.

## Container bauen

API-Service und Gateway besitzen jeweils ein mehrstufiges `Dockerfile`. Die
erste Stufe baut mit Maven das Spring-Boot-JAR, die zweite kopiert nur das JAR
in ein Java-25-Runtime-Image:

```bash
docker build --pull -t registry.example.org/ddb/ddb-api-v3-service:1.0.0 api-service
docker build --pull -t registry.example.org/ddb/ddb-api-v3-gateway:1.0.0 api-gateway

docker push registry.example.org/ddb/ddb-api-v3-service:1.0.0
docker push registry.example.org/ddb/ddb-api-v3-gateway:1.0.0
```

`--pull` aktualisiert beim Build die Basis-Images. GitHub Actions baut daraus
die zwei getrennten GHCR-Packages `ddb-api-v3-service` und
`ddb-api-v3-gateway`. Pushes auf `main` erhalten unter anderem `latest` und den
unveränderlichen Tag `sha-<Commit>`; Git-Tags der Form `v1.2.3` erzeugen
zusätzlich semantische Versionstags. Pull Requests werden nur gebaut und nicht
veröffentlicht.

Die veröffentlichten Container sind:

- [DDB API v3 Service](https://github.com/mbuechner/ddb-api-v3/pkgs/container/ddb-api-v3-service) – `ghcr.io/mbuechner/ddb-api-v3-service`
- [DDB API v3 Gateway](https://github.com/mbuechner/ddb-api-v3/pkgs/container/ddb-api-v3-gateway) – `ghcr.io/mbuechner/ddb-api-v3-gateway`

Ein separater Workflow veröffentlicht den Helm-Chart als GitHub-Pages-Repository.
Der dort enthaltene Chart verweist automatisch auf die beiden `sha-<Commit>`-
Images des gleichen Commits. Er läuft erst nach einem erfolgreichen Image-Build
und prüft beide Manifeste nochmals in GHCR. Container-Images werden nicht im
Helm-Chart gespeichert.

## Produktion

`APP_PROFILE=prod` verwendet denselben S3-Persistenzadapter wie die Entwicklung
und bindet das Gateway an Redis an. SeaweedFS-Endpunkt, Bucket und Zugangsdaten
werden über `S3_*`-Umgebungsvariablen gesetzt. Der Bucket muss in Produktion
vorab angelegt werden; `S3_CREATE_BUCKET` ist dort standardmäßig `false`.
Produktive Zugangsdaten gehören in den Secret Store.

Der Helm-Chart installiert Redis automatisch und verbindet den API-Service
mit einem extern betriebenen SeaweedFS-S3-Endpunkt. Die
[Helm-Dokumentation](charts/ddb-api-v3/README.md#seaweedfs-s3) beschreibt die
Konfiguration und Kubernetes-Secrets; [.env.example](.env.example) enthält die
vollständige Variablenreferenz.

## Speicherung in SeaweedFS

Ein validierter RDF-Datensatz wird als **unkomprimiertes Jelly RDF** in genau
einem S3-Objekt mit `Content-Type: application/x-jelly-rdf` gespeichert. Es gibt
keine zusätzliche gzip-Schicht im Speicher. Das originale Uploadformat wird
nicht aufbewahrt. Der Inhalt liegt im kanonischen Named Graph
`{Basis-URL}/records/{recordId}`, standardmäßig
`https://api.deutsche-digitale-bibliothek.de/records/{recordId}`. Die Record-Methode
verwendet beispielsweise diesen Schlüssel:

```text
J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3/record
```

Die Speicherfunktion unterstützt weitere Namen unter derselben DDB-ID, etwa
`J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3/item`. Solche Namen werden durch künftig
implementierte API-Methoden festgelegt; ein frei wählbarer Namensparameter ist
nicht Teil der aktuellen API. Jeder benannte RDF-Datensatz ist ein eigenständiges
S3-Objekt. Es gibt kein zusätzliches Manifest und keine abgeleitete Blob-Struktur.

Das Anlegen verwendet einen einzelnen S3-PUT mit `If-None-Match: *`; das
Ersetzen verwendet einen einzelnen PUT mit dem übergebenen `If-Match`. Dadurch
werden Existenz- und Änderungsbedingungen direkt am gespeicherten RDF-Objekt
geprüft. Die Region ist standardmäßig `eu-central-1`. Die Readiness-Probe prüft
den Zugriff auf den S3-Bucket.

S3-Schreibzugriffe werden nicht automatisch wiederholt. Geht eine Erfolgsantwort
unterwegs verloren, liefert die API `503`: Der Schreibausgang ist dann unklar.
Ein erneuter bedingter PUT darf daraus keinen vermeintlichen `409` oder `412`
machen.

Bestehende rohe RDF/XML-Objekte, Daten aus dem früheren Manifest-/Blob-Layout
oder aus einer H2- bzw. Cassandra-Installation werden nicht automatisch übernommen.
Ein nicht lesbares Jelly-Objekt führt bei `GET` zu `503`. Bei Bestandsdaten
ist vor dem Umschalten ein gesonderter Export und Import erforderlich. Die
Anwendung legt keine Demo-Datensätze an.

## Gemeinsame Profile

`APP_PROFILE` gilt für Service und Gateway gleichermaßen:

| Profil | API-Service | Gateway |
| --- | --- | --- |
| `dev` | SeaweedFS S3 | Caffeine |
| `prod` | SeaweedFS S3 | Redis |

Docker Compose setzt daher für beide Anwendungen `dev`. Dienstspezifische
Werte wie Ports, Speicherzugänge und `API_SERVICE_URI` bleiben getrennt, liegen
aber gemeinsam in derselben `.env`.

## Skalierung

Mehrere Instanzen sind horizontale, nicht vertikale Skalierung. Gateway und
API-Service werden unabhängig skaliert: Ein externer Load Balancer verteilt auf
beliebig viele Gateways; `API_SERVICE_URI` zeigt auf eine stabile, intern
lastverteilte Service-Adresse mit beliebig vielen API-Service-Instanzen. Eine
1:1-Zuordnung ist nicht erforderlich.

Der fachliche Zustand aller API-Service-Instanzen liegt im gemeinsamen
SeaweedFS-Bucket. Für mehrere Gateway-Instanzen ist `prod` vorgesehen, damit
deren Rate-Limit-Buckets im gemeinsamen Redis liegen. Im Profil `dev` verwaltet
jedes Gateway seine Limits lokal. Verfügbarkeit und Replikation des
produktiven SeaweedFS-Clusters werden unabhängig von der Anwendung betrieben.

## Prüfen

```bash
mvn clean verify
```

- [OpenAPI-Vertrag](api-service/src/main/openapi/openapi.yaml)
- [Persistenz in SeaweedFS](#speicherung-in-seaweedfs)
- [Rate Limiting](../apiv3-spec/docs/rate-limiting.md)
- [Kubernetes-/Helm-Bereitstellung](charts/ddb-api-v3/README.md)
- [Entwicklungskonventionen](../apiv3-spec/docs/development-conventions.md)
