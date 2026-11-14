# Architektur und Implementierung

## Datenfluss

```text
HTTP-Client → Gateway → API-Service → S3-kompatibler Objektspeicher
                │
                └→ Redis: gemeinsame Rate-Limit-Buckets in prod
```

Das Gateway übernimmt Routing, CORS und Quoten. Gateway und API prüfen
Schreibrechte unabhängig. Die API validiert RDF/XML und speichert seine Triples
als unkomprimiertes Jelly RDF. Beim Lesen liefert sie JSON-LD, RDF/XML oder Jelly.
Es gibt kein internes fachliches Metadatenmodell und keinen RDF-/Antwortcache.

Beide Anwendungen sind bezüglich der RDF-Daten stateless. API-Instanzen teilen
den S3-Bucket, produktive Gateway-Instanzen Redis. Keine Sticky Sessions und
kein gemeinsames Dateisystem sind nötig. Connection-Pools und das native
Zwischenspeichern öffentlicher JWT-Prüfschlüssel sind keine RDF-Antwortcaches.

## Packages

Unter `de.ddb.apiv3` folgen Endpunkt-Packages den API-Ressourcen:

| Package | Verantwortung |
| --- | --- |
| `records` | HTTP-Methoden, Upload-Grenzen und bedingte Schreibzugriffe |
| `items`, `datasets`, `catalog` | Vorbereitete Packages; noch keine fachlichen Endpunkte |
| `rdf` | Jena-Validierung, Jelly sowie Ausgabeadapter für JSON-LD und RDF/XML |
| `storage.s3` | Gemeinsamer Objektspeicher und dessen Verbindungskonfiguration |
| `storage`, `error` | Sichere Übersetzung von Speicherfehlern in HTTP-Antworten |
| `config` | Basis-URL, Sicherheitsregeln, Parallelitätsgrenzen und Access-Logs |
| `documentation` | Bereitstellung des Vertrags und Swagger-Einstieg |

Das Endpunkt-Package legt Objektname und Graph-IRI fest. Künftige Items oder
Organizations können denselben Speicheradapter mit eigenen Namen verwenden;
es gibt keinen frei wählbaren Dokumentnamen in der Records-API.

## Speichervertrag

Ein Record liegt als einzelnes Objekt `{recordId}/record` mit dem Content-Type
`application/x-jelly-rdf` im Bucket. Jelly enthält den benannten Graphen und
Namespace-Hinweise; es gibt keine zusätzliche gzip-Schicht und kein Manifest.
Der originale RDF/XML-Bytestrom wird nicht aufbewahrt.
Objekte verwenden Jelly 1.2 und enthalten mindestens ein Triple; leere
Uploads ergeben `422`.

Neuanlagen verwenden einen bedingten PUT mit `If-None-Match: *`, Ersetzungen
`If-Match`. Die Bedingung wird atomar durch den Speicher geprüft. Die API legt
kein zweites Versions- oder Metadatenobjekt an. S3-Schreibzugriffe werden nicht
automatisch wiederholt: Geht eine Erfolgsantwort verloren, ist der Schreibausgang
unklar und die API liefert `503`. Vor einer Wiederholung den aktuellen Record
und dessen Validator lesen.

Ein nicht lesbares Jelly-Objekt ergibt derzeit `503`; es wird nicht stillschweigend
neu angelegt oder repariert. Es gibt keine automatisch erzeugten Demo-Datensätze.

## Bibliotheken und Performance

Spring MVC übernimmt HTTP und RFC-9457-Fehler; Spring Security prüft Bearer-Tokens.
Ein kleiner Fehleradapter ergänzt Request-IDs und übersetzt Speicherfehler ohne
interne Verbindungsdetails. Authentifizierungsfehler entstehen vorher und dürfen
native leere `401`-/`403`-Antworten mit `WWW-Authenticate` haben.

Jeder GET liest das aktuelle Jelly aus S3. Der AWS-SDK-Client und seine
Apache-5-Verbindungen werden pro Instanz geteilt und beim Stoppen geschlossen.
Die Rate-Limits nutzen Bucket4j, lokal Caffeine und produktiv Redis/Lettuce.

JSON-LD verwendet Jena und Titaniums Compaction ohne erneute Expansion oder
JSON-Byte-Roundtrip. Der JSON-P-Provider wird beim Start einmal bestimmt;
eine explizite `jakarta.json.provider`-Einstellung bleibt erhalten. RDF/XML
schreibt direkt in einen gepufferten UTF-8-Writer. Unbescape übernimmt das
XML-Escaping nach Zeichenprüfung; andere Literaltypen schreibt Jena.
Der Adapter für flache, typisierte Knoten verwendet geschützte Hooks von
`RDFXML_Basic`. XML-Roundtrip-Tests sichern die Ausgabe ab.

Virtual Threads ersetzen keine Kapazitätsgrenzen: aktive RDF-Arbeit,
Warteschlangen und Socket-Pools sind begrenzt. Größenlimit und RDF-Parsing
liegen vor dem S3-Schreiben. Eine spätere SHACL-Prüfung ist durch
`TODO: SHACL ergänzen.` markiert; aktuell wird RDF/XML-Syntax validiert.

Details: [RDF-Konventionen](rdf-conventions.md), [Betrieb](operations.md),
[Skalierung](deployment.md#skalierung-und-rollouts), [Sicherheit](security.md).
Der Vertrag dieser Anwendung ist die [OpenAPI-Datei](../api-service/src/main/openapi/openapi.yaml).
Weitere Konzepte im separaten Projekt `apiv3-spec` müssen mit diesem bewusst
vereinfachten Implementierungsumfang abgeglichen werden.
