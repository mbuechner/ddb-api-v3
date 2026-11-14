# RDF und HTTP-Konventionen

## Datensätze und Formate

Die DDB-ID besteht aus genau 32 Zeichen aus `A-Z` und `2-7`. Die drei Methoden
arbeiten unter `/3/records/{recordId}`. GET/HEAD sind öffentlich; POST/PUT brauchen
Schreibrechte. Eingabe ist ausschließlich `application/rdf+xml` im Request-Body.
Ein Upload muss mindestens ein Triple enthalten. Ungültiges RDF/XML und leere
RDF-Datensätze ergeben `422`, andere Eingabeformate `415`.

Alle Triples bilden einen Named Graph mit der IRI `{öffentlicher Origin}/records/{recordId}`.
RDF/XML kann diesen Graphnamen nicht selbst transportieren; die API ergänzt ihn.
Anwendungsspezifische SHACL-Validierung ist noch nicht implementiert.

| Accept | Ausgabe |
| --- | --- |
| `application/ld+json` oder kein Header | JSON-LD mit inline `@context`, benanntem `@id` und vollständigem `@graph` |
| `application/rdf+xml` | Vollständige Triples, flache typisierte Knoten, soweit als XML-Namen darstellbar |
| `application/x-jelly-rdf` | Jelly RDF einschließlich Graphnamen |

Eingabepräfixe bleiben erhalten und werden soweit möglich für die Ausgabe
verwendet. Präfixlose Daten behalten vollständige IRIs und einen leeren Context.
Reservierte oder mehrdeutige JSON-LD-Präfixdefinitionen werden nicht erzwungen;
der RDF-Inhalt bleibt dabei erhalten. Nicht unterstützte oder für einen
Datensatz ungeeignete Ausgaben ergeben `406`.

## Ressourcen-IRIs und Basis-URL

IRIs in den Daten enthalten keine API-Version. `/records/ID` und `/items/ID`
bezeichnen Ressourcen; `/3/records/ID` ist der HTTP-Endpunkt. Versionlose öffentliche
Ressourcenpfade werden mit `308` zum versionierten Pfad weitergeleitet. Für
POST/PUT unter `/records/ID` gilt zuerst dieselbe Authentifizierung wie für den
versionierten Endpunkt. Andere schreibende Pfade bleiben gesperrt.

Redirects erhalten Methode und Querystring auf demselben Origin und legen keine
zusätzlichen fachlichen Endpunkte an. Ein noch nicht implementiertes Ziel kann
`404` liefern. Versionierte Pfade und Infrastruktur wie `/actuator`, `/openapi`,
`/webjars`, `/error`, `/contexts` und Swagger bleiben ausgenommen.

`DDB_API_BASE_URL` akzeptiert einen HTTP(S)-Origin ohne Pfad, Query oder Zugangsdaten,
beispielsweise `https://api.example.org`, oder `auto`. Optionaler Port und ein
abschließender Slash sind erlaubt. Bei `auto` kommen Schema, Host und Port aus
der Anfrage; der komplette Transportpfad einschließlich `/3` entfällt. Lokal ist
`auto` der Standard, Produktion und Helm verwenden standardmäßig
`https://api.deutsche-digitale-bibliothek.de`. Ein expliziter Origin hat Vorrang.
`/` und ein leerer Wert sind keine gültige Anwendungskonfiguration.

Bei TLS-Terminierung am Ingress empfiehlt sich ein fester öffentlicher Origin.
Native Forwarded-Auswertung nur hinter einem exklusiven, Header bereinigenden
Proxy einschalten; [Vertrauensregeln für Proxy-Adressen](logging.md#grenzen-und-schutz-vor-datenmüll).
Das HTTP-Gateway erhält den öffentlichen Host und Port für den Auto-Modus.

## Lieferdaten

`xml:base="/"` und eindeutige relative Ressourcenpfade verwenden:

```xml
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:ex="https://example.org/vocab/" xml:base="/">
  <rdf:Description rdf:about="records/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA">
    <ex:related rdf:resource="items/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"/>
  </rdf:Description>
</rdf:RDF>
```

Auf `http://localhost:8080` entstehen daraus `/records/…` und `/items/…` unter
diesem Origin. Ohne `xml:base="/"` die Referenzen mit `/records/…` bzw. `/items/…`
beginnen; sonst werden sie relativ zum Record-Pfad aufgelöst. Mit `xml:base="/"`
bedeutet `rdf:about=""` die Origin-Wurzel und `#fragment` ein Fragment dort,
nicht den Record. Deshalb Ressourcenpfade ausdrücklich angeben.

Eine absolute XML-Basis bestimmt weiterhin die Auflösung relativer Referenzen.
Bereits absolute Ressourcen- und Vokabular-IRIs werden nicht umgeschrieben.
Den Graphnamen vergibt die API davon unabhängig. Die Basisänderung gilt nur
für neue POST-/PUT-Zugriffe; GET erhält gespeicherte IRIs und Graphnamen.

## Anlegen, ersetzen und bedingt lesen

`ACCESS_TOKEN` steht hier für ein gültiges Bearer-Token; [Einrichtung](security.md).

```bash
curl -i -X POST \
  http://localhost:8080/3/records/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/rdf+xml' --data-binary @record.rdf
```

Neuanlage ergibt `201` mit `Location` und starkem `ETag`; eine bereits vorhandene
ID ergibt `409`. Zum Ersetzen PUT mit `If-Match: "aktueller-Validator"` senden.
Der starke Validator steht bei GET und nach erfolgreichem PUT in `X-Record-ETag`.
PUT ergibt `204` ohne Body und ohne `ETag`. Eine fehlende Bedingung ergibt `428`,
eine veraltete, schwache, ungültige oder nicht passende Bedingung `412`.
Auch ein unbekannter Record erfüllt die Ersetzungsbedingung nicht.

Eine Liste starker ETags ist möglich; schwache Tags passen nicht. `If-Match: *`
verlangt nur, dass der Record existiert. Bei `If-None-Match` wird dagegen schwach
verglichen; ein Treffer oder `*` für einen vorhandenen Record ergibt `304`.
GET liefert einen schwachen `ETag` für den RDF-Datensatz. Für Schreibbedingungen
stets den starken `X-Record-ETag` verwenden. Fehlerhafte DDB-IDs ergeben beim
Lesen `404`, bei authentifizierten Schreibzugriffen `400`.

Gzip wird für Requests und passende Responses unterstützt. Das Größenlimit gilt
für den dekomprimierten RDF-Body vor der RDF-Verarbeitung. Swagger verwendet
die Auswahl des Antwort-Medientyps als `Accept`; nach einem Wechsel erneut
**Execute** wählen. Seine Testanfragen verwenden `cache: no-store`.
