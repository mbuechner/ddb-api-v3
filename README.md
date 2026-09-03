# DDB API v3

OpenAPI-first-Referenzimplementierung mit Spring-MVC-API-Service und
Spring-Cloud-Gateway. Lokal speichert der Service in H2; Produktion verwendet
eine externe Cassandra-Datenbank. Das Gateway begrenzt Zugriffe lokal im
Prozess und produktiv verteilt über Redis.

## Implementierungsstand

Implementiert sind das Erstellen und Aktualisieren eines Records, der Abruf der
administrativen DCAT-Metadaten sowie `GET` und `HEAD` des RDF-Inhalts. Die
OpenAPI-Spezifikation beschreibt darüber hinaus den geplanten vollständigen
API-v3-Vertrag; noch nicht implementierte Operationen antworten mit
`501 Not Implemented`.

Die OpenAPI-Spezifikation deklariert OAuth2-Scopes, die Anwendung enthält aber
noch keinen Resource Server. Schreibzugriffe dürfen deshalb nicht öffentlich
bereitgestellt werden, bevor Authentifizierung und Scope-Prüfung ergänzt wurden.
Das Produktions-Gateway arbeitet ohne authentifizierten Principal bewusst
fail-closed und antwortet auf `/3/**` mit `403 Forbidden`.

## Lokal starten

Voraussetzung sind Java 25 und Maven. Der API-Service startet ohne externe
Datenbank mit dem Standardprofil `dev`:

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

H2 speichert dabei dauerhaft unter `api-service/data/ddb-api-v3.mv.db`. Die lokale
H2-Konsole ist unter <http://localhost:8080/h2-console> erreichbar. Anmeldung:
JDBC URL `jdbc:h2:file:./data/ddb-api-v3`, Benutzer `sa`, leeres Passwort.
Pfad und Zugangsdaten lassen sich über die in [.env.example](.env.example)
dokumentierten `H2_*`-Variablen ändern. Bestehende Daten werden beim Neustart
nicht überschrieben.

Für API-Service und Gateway gemeinsam kann Docker verwendet werden:

```bash
cp .env.example .env
docker compose up --build
```

Swagger UI ist dann unter <http://localhost:8081/swagger-ui.html> erreichbar.
Das benannte Volume `h2-data` erhält die lokale Datenbank auch bei einem
Neustart oder erneuten Erstellen des API-Service-Containers. Die H2-Konsole ist
im Compose-Betrieb nicht öffentlich erreichbar.
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

`APP_PROFILE=prod` aktiviert Cassandra und deaktiviert H2. Beim direkten Betrieb
werden Cassandra und Redis über Umgebungsvariablen angebunden. Der Helm-Chart
installiert Redis dagegen automatisch im Cluster; dort muss nur Cassandra
extern bereitgestellt werden. Die vollständige Variablenreferenz steht in
[.env.example](.env.example).

## Gemeinsame Profile

`APP_PROFILE` gilt für Service und Gateway gleichermaßen:

| Profil | API-Service | Gateway |
| --- | --- | --- |
| `dev` | H2 | Caffeine |
| `prod` | Cassandra | Redis |

Docker Compose setzt daher für beide Anwendungen `dev`. Dienstspezifische
Werte wie Ports, Datenbankzugänge und `API_SERVICE_URI` bleiben getrennt, liegen
aber gemeinsam in derselben `.env`.

## Skalierung

Mehrere Instanzen sind horizontale, nicht vertikale Skalierung. Gateway und
API-Service werden unabhängig skaliert: Ein externer Load Balancer verteilt auf
beliebig viele Gateways; `API_SERVICE_URI` zeigt auf eine stabile, intern
lastverteilte Service-Adresse mit beliebig vielen API-Service-Instanzen. Eine
1:1-Zuordnung ist nicht erforderlich.

Nur `prod` ist dafür ausgelegt: fachlicher Zustand liegt in Cassandra und die
Rate-Limit-Buckets liegen im gemeinsamen Redis. `dev` verwendet H2 und ist beim
API-Service deshalb nicht horizontal skalierbar.

## Prüfen

```bash
mvn clean verify
```

- [OpenAPI-Vertrag](api-service/src/main/openapi/openapi.yaml)
- [Administrative Metadaten und URLs](docs/metadata.md)
- [RDF-Inhalte und Jena-Konvertierung](docs/content.md)
- [Persistenz](docs/persistence.md)
- [Rate Limiting](docs/rate-limiting.md)
- [Kubernetes-/Helm-Bereitstellung](charts/ddb-api-v3/README.md)
- [Entwicklungskonventionen](docs/development-conventions.md)
