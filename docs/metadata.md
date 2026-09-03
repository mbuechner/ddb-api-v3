# Administrative Metadaten

`GET /3/records/{recordId}` liefert `application/ld+json`. Die Antwort ist ein
`dcat:CatalogRecord`; sein `foaf:primaryTopic` ist der beschriebene Einzelrecord,
den die API als `dcat:Dataset` modelliert.

Der externe, versionierte JSON-LD-Context liegt unter
`/contexts/record-metadata-v1.jsonld`. Eine semantische Änderung erfordert einen
neuen Context-Pfad.

## Zuordnung

| JSON-Feld | RDF-Begriff |
| --- | --- |
| `primaryTopic` | `foaf:primaryTopic` |
| `recordId` | `dcterms:identifier` |
| `datasets` | `dcterms:isPartOf` |
| `provider` | `dcterms:publisher` |
| `aggregator` | `edm:intermediateProvider` |
| `sourceIdentifier` | `adms:identifier` |
| `publicationStatus` | `adms:status` |
| `invalidatedAtTime` | `prov:invalidatedAtTime` |
| `createdAt`, `modifiedAt` | `dcterms:issued`, `dcterms:modified` |
| `deletionReason` | `dcterms:type` |
| `deletionComment` | `dcterms:description` |
| `validationAssertion` | inverse Beziehung über `earl:subject` |

## Tombstones

Ein intern als `DELETED` markierter Record bleibt als Tombstone adressierbar:

- Der Metadaten-Endpunkt liefert `200`, den aktuellen ETag,
  `publicationStatus = …/WITHDRAWN` und `invalidatedAtTime`.

- `deletionReason` ist verpflichtend und enthält die absolute IRI eines Begriffs
  aus einem kontrollierten Löschgrund-Vokabular. `deletionComment` ist ein
  optionaler, öffentlicher Freitext und darf keine vertraulichen oder
  personenbezogenen Daten enthalten. Beide Felder stehen direkt am Record.

- `GET` und `HEAD` auf `/content` liefern `410 RECORD_GONE` und keinen
  Record-Inhalt.

`WITHDRAWN` ohne `invalidatedAtTime` bezeichnet lediglich einen zurückgezogenen
Record. Ein permanentes Purge entfernt auch den Tombstone und führt zu `404`.

## Späterer DCAT-Wrapper

Der Wrapper sollte ein `dcat:Catalog` sein. Ein Catalog ist selbst ein
`dcat:Dataset`, kann Metadateneinträge aber ausdrücklich über `dcat:record`
enthalten. Die beschriebenen Einzelrecords werden zusätzlich über `dcat:dataset`
gelistet.

```json
{
  "@context": {
    "dcat": "http://www.w3.org/ns/dcat#",
    "foaf": "http://xmlns.com/foaf/0.1/",
    "records": { "@id": "dcat:record", "@container": "@set" },
    "datasets": { "@id": "dcat:dataset", "@type": "@id", "@container": "@set" },
    "primaryTopic": { "@id": "foaf:primaryTopic", "@type": "@id" }
  },
  "@id": "/catalogs/example",
  "@type": "dcat:Catalog",
  "records": [{
    "@id": "/3/records/ABC#metadata",
    "@type": "dcat:CatalogRecord",
    "primaryTopic": "https://resources.example/item/ABC"
  }],
  "datasets": ["https://resources.example/item/ABC"]
}
```

Die fachlichen Sammlungen bleiben eigene `dcat:Dataset`-Ressourcen. Ein
Einzelrecord kann mit mehreren `dcterms:isPartOf`-Beziehungen zu mehreren
Sammlungen gehören. Die aggregierende Organisation wird zusätzlich als
`edm:intermediateProvider`-IRI ausgegeben. So bleiben Katalogmetadaten,
Einzelrecord, Sammlungen und Organisationen getrennte Ressourcen.

## URLs

| Variable | Verwendung |
| --- | --- |
| `DDB_API_PUBLIC_BASE_URL` | Context, `dcat:CatalogRecord`, Validierungsassertion und Profil-IRIs |
| `DDB_RESOURCE_BASE_URL` | Einzelrecords, Datasets und Organisationen |

Beide Werte müssen absolute HTTP(S)-URLs ohne Benutzerinfo, Query oder Fragment
sein. Persistiert werden nur IDs und Fakten; öffentliche IRIs entstehen erst in
der HTTP-Schicht.

Verwendete Standards: [DCAT 3](https://www.w3.org/TR/vocab-dcat-3/),
[PROV-O](https://www.w3.org/TR/prov-o/),
[ADMS](https://www.w3.org/TR/vocab-adms/) und
[EARL](https://www.w3.org/TR/EARL10-Schema/).
