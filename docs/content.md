# RDF-Inhalte

`GET /3/records/{recordId}/content` liefert genau den durch `Accept` gewählten
RDF-Medientyp. Fehlt `Accept` oder enthält er `*/*`, wird das kanonische
`application/rdf+xml` gewählt.

RDF/XML liegt in beiden Betriebsarten in der Spalte `rdf_xml`. Der
Persistenzadapter liest diese Spalte mit einer eigenen Point-Lookup-Abfrage;
Metadatenaufrufe laden den BLOB daher nicht. Bei `application/rdf+xml` gibt der
Service die gespeicherten Bytes unverändert über Springs `Resource`-Writer aus.
Apache Jena wird in diesem Pfad weder zum Parsen noch zum Schreiben verwendet.

Nur für einen anderen Medientyp liest Apache Jena RIOT das RDF/XML und schreibt
die gewählte Darstellung:

| Darstellung | Medientyp |
| --- | --- |
| JSON-LD | `application/ld+json` |
| Turtle | `text/turtle` |
| N-Triples | `application/n-triples` |
| N-Quads | `application/n-quads` |
| RDF/JSON | `application/rdf+json` |
| TriG | `application/trig` |
| TriX | `application/trix` |
| RDF Thrift | `application/rdf+thrift` |
| RDF Protobuf | `application/rdf+protobuf` |

N-Quads, TriG und TriX sind Dataset-Syntaxen. Sie verwenden die über
`DDB_API_PUBLIC_BASE_URL` konfigurierte URL `/3/records/{recordId}` als Namen des
einzigen Graphen. Alle anderen Zielformate repräsentieren den Graphen direkt.

ETag und `Last-Modified` werden vor einer möglichen Konvertierung ausgewertet.
Ein `304 Not Modified` sowie `HEAD` starten deshalb keine Jena-Serialisierung.
ETags abgeleiteter Formate enthalten die Jena-Version, sodass ein Upgrade mit
potenziell anderer Byteausgabe den Validator ändert. `X-Content-SHA256` bezeichnet
stets das kanonische, unkomprimierte RDF/XML.

Die Konvertierung benötigt naturgemäß ein Jena-Modell im Arbeitsspeicher. Der
kanonische Pfad vermeidet dieses Modell vollständig. H2 und der Cassandra-Treiber
materialisieren den einzelnen BLOB aktuell einmal als Byte-Array; die HTTP-Ausgabe
selbst erfolgt über den vorhandenen Spring-`Resource`-Writer.
