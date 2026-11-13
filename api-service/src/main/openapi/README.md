# OpenAPI-Vertrag der RDF-API

`openapi.yaml` beschreibt `GET`, `POST` und `PUT` für `/3/records/{recordId}`.
Maven validiert die Datei; der Service liefert sie für Swagger UI unverändert
unter `/openapi/openapi.yaml` aus.

Nach einer Änderung aus dem Repository-Wurzelverzeichnis prüfen:

```bash
mvn clean verify
```

Die kleinen Controller sind handgeschrieben. `InputStreamResource` hält den
Request-Body offen, bis der Upload-Service ihn mit Größenlimit einliest.
Apache Jena liest das durch `Content-Type` gewählte RDF-Format. Alle Triples
werden im Named Graph `{Basis-URL}/records/{recordId}` vereinigt. Die Basis steht
in `ddb.api.base-url` beziehungsweise `DDB_API_BASE_URL` und lautet standardmäßig
`https://api.deutsche-digitale-bibliothek.de`. Sie wird aus der Konfiguration
gelesen, nicht aus Request-Headern. Das Ergebnis wird als unkomprimiertes Jelly RDF
gespeichert. `GET` erhält vorhandene Graphnamen und wandelt Jelly
in das mit `Accept` gewählte Jena-Format um; ohne Vorgabe ist es TriG.
Dataset-Formate enthalten den Graphnamen, Graph-Formate dessen vollständige Triples.
Fehler nutzen Springs `ProblemDetail`; eigene Metadaten-DTOs sind nicht nötig.

Die Implementierung von `/records` liegt im Package `de.ddb.apiv3.records`.
Gemeinsame RDF-Verarbeitung liegt unter `rdf`, die Speicherabstraktion
`RdfDocumentStore` unter `storage` und deren S3-Implementierung unter `storage.s3`.
Das Package `items` ist bereits dokumentiert; ein Items-Endpunkt ist noch nicht
implementiert und deshalb nicht Teil dieses Vertrags.

Das separate Projekt `apiv3-spec` kann einen weitergehenden Planungsstand
enthalten. Änderungen vor einer Übernahme mit diesem RDF-Vertrag abgleichen.
