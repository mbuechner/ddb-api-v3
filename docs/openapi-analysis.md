# Analyse der vorhandenen OpenAPI-Spezifikation

## Überblick

- OpenAPI-Version: 3.1.0
- API-Version: 3.0.0-dev
- Aufbau: eine YAML-Datei, keine externen `$ref`
- Paths: 9 Record-Ressourcen mit 17 Operationen
- Operationen: alle besitzen eindeutige, konsistente `operationId`-Werte
- Tags: Records
- Security: global OAuth2 Client Credentials mit `records:read`; öffentliche Leseoperationen überschreiben dies mit
  der anonymen Alternative `{}` und optionalem OAuth2-Scope
- Request Bodies: multipart/form-data für Record-Schreiboperationen; JSON-Metadaten plus RDF/XML und optionale
  Source-Datei
- Response-Formate: DCAT-3-JSON-LD für administrative Metadaten, JSON für andere strukturierte Antworten,
  Problem JSON, RDF/XML, Turtle, N-Triples, N-Quads sowie weitere RDF- und XML-Varianten
- Fehler: zentrales `Problem`-Schema und wiederverwendete Fehler-Responses
- Pagination: für die vorhandenen Record-Ressourcen nicht vorgesehen
- Validierung: umfangreiche Required-, Pattern-, Längen-, Wertebereich-, Array- und Enum-Regeln

Der OpenAPI-Generator-Validator 7.25.0 meldet keine Fehler. Die verbleibenden Empfehlungen betreffen logische
Multipart-/Batch-Schemas, die über herstellerspezifische Erweiterungen mit den Streaming-Operationen verbunden sind.

## Implementierungsstand

Die Spezifikation beschreibt bewusst den vollständigen geplanten API-v3-Vertrag.
Die Referenzimplementierung überschreibt derzeit `createRecord`, `putRecord`,
`getRecord`, `getRecordContent` und `headRecordContent`. Nicht überschriebene
Delegate-Methoden antworten mit `501 Not Implemented`; sie sind vorgesehener
Vertragsumfang und kein ungenutzter Code.

## MUSS

1. **OAuth2 vor Schreibfreigabe implementieren.** Der Vertrag schützt Mutationen mit `records:write` bzw.
   `records:delete`. Die Schreiblogik ist implementiert; ein echter Resource Server und Scope-Prüfungen sind
   dennoch Pflicht, bevor sie bereitgestellt wird.
2. **Bedingte Regeln serverseitig prüfen.** Das implementierte PUT verlangt den
   aktuellen `If-Match`-ETag. Die Spezifikation verlangt für das noch nicht
   implementierte Batch-Schreiben genau eines von `If-Match` und
   `If-None-Match: *`. Die vorhandene Schreiblogik prüft außerdem die
   Abhängigkeiten der optionalen Source-Metadaten.

## SOLLTE

1. **Inline-Schemas benennen.** Der Generator erzeugt interne Namen für den Multipart-Request und
   `Problem.errors` (`createRecord_request`, `Problem_errors_inner`). Explizite `title`-Werte oder eigenständige
   Komponenten würden stabilere generierte Namen liefern.
2. **Nicht standardmäßig validierte Formate absichern.** `http-date` und `uri-reference` werden in Java teilweise
   als einfacher String generiert. Falls strikte Syntax wichtig ist, sind Pattern/Format-Validatoren oder passende
   Typ-Mappings erforderlich.
3. **Problem-Erweiterungen bewusst begrenzen.** `Problem.additionalProperties: true` ist flexibel, erschwert aber
   streng validierte Fehlerclients. Eine definierte Extension-Konvention wäre wartbarer.

## OPTIONAL

1. Die vielen wiederholten Response-Header könnten über zusätzliche Response-Komponenten weiter gebündelt werden;
   aktuell sind zumindest die Header-Schemas bereits zentral wiederverwendet.
2. Beispiele können um konkrete Fehler- und Conditional-GET-Fälle ergänzt werden.
3. Bei einer späteren Aufteilung in mehrere Dateien sollten relative `$ref`-Pfade im Maven-Validator als gesamter
   Verzeichnisbaum validiert werden. Aktuell existieren keine externen Referenzen.

## Generatorentscheidung

Verwendet wird das Delegate Pattern statt eines reinen `interfaceOnly`-Setups. Damit generiert das Plugin sowohl das
Spring-MVC-Mapping als auch das Delegate-Interface; die Anwendung implementiert ausschließlich das Delegate und
behält Businesslogik im Service. `interfaceOnly=true` würde einen eigenen Controller mit zusätzlichem Spring-
Boilerplate erfordern. `documentationProvider=source` wurde geprüft, aber nicht benötigt: Die Originaldatei wird
als Maven-Ressource unverändert unter `/openapi/openapi.yaml` ausgeliefert, und die statische Swagger UI lädt genau
diese Quelle. Dadurch entsteht keine zweite OpenAPI-Spezifikation.

OpenAPI 3.1 wird vom eingesetzten Generator weiterhin als Beta ausgewiesen. Der Build validiert und kompiliert den
aktuellen Vertrag erfolgreich; Generator-Upgrades sollten daher immer über `mvn clean verify` und die Contract-
Tests abgesichert werden.

Die Content-Repräsentationen verwenden inzwischen gemeinsam `RdfRepresentation`
(`string`/`binary`). Dadurch erzeugt der Spring-Generator für alle Medientypen
`ResponseEntity<Resource>` statt des früheren, ungeeigneten
`ResponseEntity<Map<String,Object>>`. `RdfXmlDocument` ist aus demselben Grund auch
für eingehende RDF/XML-Streams binär modelliert.
