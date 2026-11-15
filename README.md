# DDB API v3

RDF/XML rein → JSON-LD (Default), RDF/XML oder Jelly RDF raus.
Persistenz: unkomprimiertes Jelly über S3. API und Gateway sind stateless;
kein RDF-/Antwortcache, keine Demo-Daten.

| Methode | `/3/records/{recordId}` |
| --- | --- |
| `GET`, `HEAD` | Öffentlich lesen |
| `POST` | Anlegen; Bearer-Token |
| `PUT` | Ersetzen; Bearer-Token + `If-Match` |

IDs: 32 Zeichen, `[A-Z2-7]{32}`. Vertrag: [OpenAPI](api-service/src/main/openapi/openapi.yaml).

## Start

Docker + Compose. `.env` einmal anlegen; vorhandene Secrets behalten:

```bash
cp .env.example .env
docker compose up --build
```

Swagger: <http://localhost:8081/swagger-ui.html>. Das Storage-Volume ist persistent;
`docker compose down -v` löscht lokale Daten.

Alternativ Java 25 + Maven; S3 vorher starten, dann beide Anwendungen gemeinsam:

```bash
docker compose up -d seaweedfs
mvn spring-boot:run
```

Der Maven-Aufruf im Projektverzeichnis startet API und Gateway parallel;
`Strg+C` beendet beide. Einzelstarts bleiben über `mvn -pl api-service spring-boot:run`
beziehungsweise `mvn -pl api-gateway spring-boot:run` möglich.

API: `:8080`, Gateway: `:8081`, lokales S3: `:8333`.
Freie, unterschiedliche Werte für `DDB_API_PORT` und `GATEWAY_PORT` wählen.
Compose veröffentlicht das Gateway auf demselben `GATEWAY_PORT`, auf dem es lauscht.

## Konfiguration

[.env.example](.env.example) ist die Variablenreferenz. Prozess-ENV gewinnt;
Änderungen brauchen Neustarts. `.env` nicht einchecken.

`DDB_API_*` konfiguriert die API einschließlich S3, `GATEWAY_*` das Gateway
einschließlich Redis und Rate-Limits. Profil (`APP_PROFILE`), Dev-Token und
OAuth-Vertrauen stehen in einer gemeinsamen Gruppe für beide Anwendungen.

| Profil | RDF-Speicher | Gateway-Limits |
| --- | --- | --- |
| `dev` (Default) | S3 | Prozesslokal, kein Redis nötig |
| `prod` | S3 | Gemeinsames Redis |

- **Writes:** Ohne Auth-Konfiguration gesperrt. Lokal `DDB_DEV_WRITE_TOKEN`
  erzeugen; produktiv OAuth2/OIDC + `records:write`. Dev-Token in `prod`: Startfehler.
  [Token-Erzeugung und Swagger](docs/security.md).
- **S3:** Produktiv vorhandener Bucket, Objekt-Lese-/Schreibrechte + `HeadBucket`.
  Region: `eu-central-1`. Secrets extern verwalten.
- **IRIs:** `DDB_API_BASE_URL=auto` oder öffentlicher Origin. Lieferdaten mit
  `xml:base="/"`; [RDF-Konventionen](docs/rdf-conventions.md).
- **Skalierung:** API/Gateway unabhängig; mehrere Gateways mit `prod` + Redis.
  Keine Sticky Sessions. [Helm/OpenShift](charts/ddb-api-v3/README.md).
- **Health:** `/actuator/health/{liveness,readiness}`. Readiness prüft S3/Redis.
  JSON-Logs auf stdout; Nutzungszahlen am Gateway zählen.

## Build

```bash
mvn clean verify
```

Docker-basierte Tests brauchen eine Docker-Engine; Skips vor Releases prüfen.
Beide Module haben eigene Dockerfiles. [Images und Releases](docs/operations.md#container-und-releases).

## Details

[Betrieb/S3/Diagnose](docs/operations.md) · [RDF/IRIs/ETags](docs/rdf-conventions.md) ·
[Architektur](docs/architecture.md) · [Deployment/Skalierung](docs/deployment.md) ·
[Security/Keycloak](docs/security.md) · [Logs/Statistiken](docs/logging.md)
