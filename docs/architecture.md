# Architektur und Implementierung

## Datenfluss

```text
HTTP-Client → Gateway → API-Service → S3-kompatibler Objektspeicher
                │
                └→ Redis: gemeinsame Rate-Limit-Buckets in prod
```

Das Gateway übernimmt Routing, CORS und Quoten. Gateway und API prüfen
Schreibrechte unabhängig. Die API parst RDF/XML, prüft seinen Graphen gegen
endpunktspezifische SHACL-Shapes und speichert die Triples als unkomprimiertes
Jelly RDF. Beim Lesen liefert sie JSON-LD, RDF/XML oder Jelly.
Es gibt kein internes fachliches Metadatenmodell und keinen RDF-/Antwortcache.

Beide Anwendungen sind bezüglich der RDF-Daten stateless. API-Instanzen teilen
den S3-Bucket, produktive Gateway-Instanzen Redis. Keine Sticky Sessions und
kein gemeinsames Dateisystem sind nötig. Connection-Pools und das native
Zwischenspeichern öffentlicher JWT-Prüfschlüssel sind keine RDF-Antwortcaches.

## Packages

Unter `de.ddb.apiv3.api` sind die HTTP-Endpunkte gebündelt. Die darunterliegenden
Ressourcen-Packages folgen den URL-Kollektionen; gemeinsame Verarbeitung liegt
außerhalb von `api` unter `de.ddb.apiv3`:

| Package | Verantwortung |
| --- | --- |
| `api.records`, `api.items` | Je ein dünner Controller, feste Dokumentidentität und eigener SHACL-Validator |
| `api` | Versionlose Ressourcenpfade zum versionierten HTTP-Endpunkt weiterleiten |
| `api.documentation` | Bereitstellung des Vertrags und Swagger-Einstieg |
| `documents` | Gemeinsames Anlegen, Ersetzen und Lesen: Größenlimit, ETags, Graph-IRI und Speicherzugriff |
| `rdf` | Einmaliges RDF-Parsing, Jelly sowie Ausgabeadapter für JSON-LD und RDF/XML |
| `rdf.validation` | Gemeinsamer SHACL-Adapter; Shapes einmal laden, jeden Upload separat prüfen |
| `storage.s3` | Gemeinsamer Objektspeicher und dessen Verbindungskonfiguration |
| `storage`, `error` | Sichere Übersetzung von Speicherfehlern in HTTP-Antworten |
| `config` | Basis-URL, Sicherheitsregeln, Parallelitätsgrenzen und Access-Logs |

Jeder Controller hält einen einmal angelegten `RdfDocumentType`: URL-Kollektion,
S3-Dokumentname, Revisionsheader und Validator sind darin explizit zugeordnet.
`RdfDocumentService` übernimmt die gemeinsame Verarbeitung; die Controller behalten
ihre sichtbaren POST-/PUT-/GET-Mappings. Es gibt weder eine dynamische
Endpunkt-Registry noch frei wählbare S3-Dokumentnamen im HTTP-Vertrag.

```text
src/main/
├── java/de/ddb/apiv3/
│   ├── documents/      RdfDocumentType, RdfDocumentService
│   ├── api/
│   │   ├── records/       RecordsController, RecordShaclValidator
│   │   ├── items/         ItemsController, ItemShaclValidator
│   │   ├── documentation/ ApiDocumentationController
│   │   └── ApiVersionRedirectController
│   ├── rdf/            RdfCodec, Ausgabeadapter
│   │   └── validation/ RdfValidator, ShaclRdfValidator
│   └── storage/s3/     S3RdfDocumentStore, Verbindungskonfiguration
└── resources/shacl/  Git-Submodul: ddb-metadata-model
    ├── records.ttl
    └── items.ttl
```

| Endpunkt | S3-Objekt | Named Graph | Starker Revisionsheader | Validator / Shapes |
| --- | --- | --- | --- | --- |
| `/3/records/{recordId}` | `{recordId}/record` | `{Origin}/records/{recordId}` | `X-Record-ETag` | `RecordShaclValidator` / `shacl/records.ttl` |
| `/3/items/{itemId}` | `{itemId}/item` | `{Origin}/items/{itemId}` | `X-Item-ETag` | `ItemShaclValidator` / `shacl/items.ttl` |

## Speichervertrag

Ein Record liegt als einzelnes Objekt `{recordId}/record`, ein Item als
`{itemId}/item` mit dem Content-Type `application/x-jelly-rdf` im Bucket.
Jelly enthält den benannten Graphen und
Namespace-Hinweise; es gibt keine zusätzliche gzip-Schicht und kein Manifest.
Der originale RDF/XML-Bytestrom wird nicht aufbewahrt.
Objekte verwenden Jelly 1.2 und enthalten mindestens ein Triple; leere
Uploads ergeben `422`.

Neuanlagen verwenden einen bedingten PUT mit `If-None-Match: *`, Ersetzungen
`If-Match`. Die Bedingung wird atomar durch den Speicher geprüft. Die API legt
kein zweites Versions- oder Metadatenobjekt an. S3-Schreibzugriffe werden nicht
automatisch wiederholt: Geht eine Erfolgsantwort verloren, ist der Schreibausgang
unklar und die API liefert `503`. Vor einer Wiederholung das aktuelle Dokument
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
liegen vor dem S3-Schreiben. Ein Upload wird nur einmal in einen Jena-Graphen
geparst. Derselbe Graph durchläuft seinen endpunktspezifischen SHACL-Validator
und wird danach als Jelly kodiert. GET löst keine SHACL-Prüfung aus. Die
kompilierten Shapes werden einmal je Validator beim Start geladen und wiederverwendet;
Graphen und Validierungsergebnisse bleiben request-lokal.

## Endpunkte und SHACL erweitern

`RecordShaclValidator` und `ItemShaclValidator` binden jeweils eine eigene
Classpath-Datei ein. `ShaclRdfValidator` enthält nur das gemeinsame Laden und
Prüfen; `RdfValidator` ist die kleine Schnittstelle zum RDF-Codec. Fehlende oder
ungültige Shape-Dateien verhindern den Start. Die Default-Shapes prüfen die
bisherige Mindestanforderung eines nichtleeren Graphen; fachliche Pflichtfelder
werden nicht erfunden.

Für zusätzliche Regeln die jeweilige Datei unter
`api-service/src/main/resources/shacl/` im separaten Repository
[ddb-metadata-model](https://github.com/Deutsche-Digitale-Bibliothek/ddb-metadata-model) erweitern und dort committen.
Das Verzeichnis ist ein Git-Submodul; dieses Projekt referenziert einen festen
Commit. Maven und Docker verpacken ausschließlich `shacl/items.ttl` und
`shacl/records.ttl` aus dem Submodul unverändert im Anwendungs-JAR. Buildskripte,
Tests, Entwürfe und Dokumentation des Modellprojekts bleiben draußen.
Beim Build und zur Laufzeit werden keine neuen SHACL-Stände aus dem Netz geladen.

Aktiv sind `records.ttl` und `items.ttl` mit der Prüfung auf einen nichtleeren
Graphen. Die zusätzlichen `*_shapes.ttl` sind fachliche Arbeitsentwürfe und
werden von den Validatoren nicht geladen. Die `*_class_definitions.ttl` und
`*_property_definitions.ttl` dokumentieren das RDF-Vokabular.

Einen neuen Stand aus dem SHACL-Repository gezielt übernehmen; `<Commit>` durch
den gewünschten Commit ersetzen:

```bash
git -C api-service/src/main/resources/shacl fetch origin
git -C api-service/src/main/resources/shacl checkout <Commit>
mvn -pl api-service clean verify
git add api-service/src/main/resources/shacl
git commit -m "Update SHACL profiles"
```

Positive sowie negative Uploadfälle für genau diesen Endpunkt testen. Nach dem
Neubau der Anwendung und ihrem Neustart gilt der übernommene Stand. Andere
Checkouts übernehmen ihn mit `git submodule update --init --recursive`; die CI
checkt das Submodul ebenfalls auf dem referenzierten Commit aus.

Für einen weiteren Ressourcentyp dasselbe Muster verwenden: expliziter
Controller und eigener Validator unter `api/{ressource}`, eigene Shapes-Datei
und feste `RdfDocumentType`-Zuordnung.
Außerdem seine Schreib-Scopes in API und Gateway und seinen OpenAPI-Pfad ergänzen.
Gemeinsame Schreib-/Lese-, ETag-, S3- und RDF-Implementierung bleiben unverändert.

Details: [RDF-Konventionen](rdf-conventions.md), [Betrieb](operations.md),
[Skalierung](deployment.md#skalierung-und-rollouts), [Sicherheit](security.md).
Der Vertrag dieser Anwendung ist die [OpenAPI-Datei](../api-service/src/main/openapi/openapi.yaml).
Weitere Konzepte im separaten Projekt `apiv3-spec` müssen mit diesem bewusst
vereinfachten Implementierungsumfang abgeglichen werden.
