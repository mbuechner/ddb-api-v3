# DDB RDF-/SHACL-Anwendungsprofile (Arbeitsentwurf 0.1)

**Grundlage:** „Konzept für ein neues Datenmodell für das DDB-Objekt“, Version 0.1, 06.11.2026, sowie die DDB-Entscheidung, vier API-Bereiche voneinander zu trennen. Datenbeispiele dienen zur technischen Plausibilisierung, nicht als Grundlage der beschreibenden Fachtexte.

## Struktur

| Bereich | Klassendatei | Propertydatei | SHACL-Core-Regeln |
|---|---|---|---|
| `/items` | `items_class_definitions.ttl` | `items_property_definitions.ttl` | `items_shapes.ttl` |
| `/records` | `records_class_definitions.ttl` | `records_property_definitions.ttl` | `records_shapes.ttl` |
| `/datasets` | `datasets_class_definitions.ttl` | `datasets_property_definitions.ttl` | `datasets_shapes.ttl` |
| `/catalog` | `catalog_class_definitions.ttl` | `catalog_property_definitions.ttl` | `catalog_shapes.ttl` |

**Wichtig:** Klassen- und Propertydateien sind RDF-Vokabulardokumentation. Die vier `*_shapes.ttl`-Dateien enthalten die ausführbaren SHACL-Bedingungen. Weder die Vokabulardokumentation noch die Start-Shapes allein sind ein vollständiges, fachlich beschlossenes Validierungsprofil.

## Fachliche Grenzen

- `/items`: Kulturerbeobjekte, Aggregationen, Proxys, digitale Repräsentationen und Kontextressourcen. Die Struktur differenziert zwischen übermittelten und durch DDB angereicherten Beschreibungen; es werden keine festen Anzahlen von Proxys vorgeschrieben.
- `/records`: Administrative Records, Provenienz, technische Anreicherungsmetadaten und Qualitätsbewertung. Die fachliche Definition eigener technischer Anreicherungsklassen bleibt offen. Es werden PROV-O und DQV eingesetzt, ohne solche DDB-Klassen zu erfinden. `dcat:CatalogRecord` ist optional und beschreibt einen Katalogeintrag, nicht pauschal jeden Record.
- `/datasets`: Lieferdatensets und Distributionen mit DCAT 3.
- `/catalog`: Katalog und Datendienste mit DCAT 3.
- Bezeichnungen (`rdfs:label`) und geeignete Textwerte können beliebige BCP-47-Sprachtags tragen. Deutsch ist redaktionell bevorzugt, `@en` und weitere Sprachversionen sind zulässig.
- Der offizielle DQV-Namespace lautet `http://www.w3.org/ns/dqv#`, nicht die URL der W3C-Dokumentation.
- DDB-Begriffe und technische `ddbv:`-Profilklassen sind Entwurfsbestandteile. Ihre Veröffentlichung und Namenskonventionen müssen noch beschlossen werden.
- Gemeinsame standardisierte Eigenschaften (z. B. `dcterms:title`) können in mehreren Bereichs-Katalogen dokumentiert sein; das ist keine doppelte Datenhaltung.
- `rdfs:subClassOf` auf `ddbv:`-Hilfsklassen beschreibt die lokale Validierungsgruppierung, nicht notwendigerweise die Ontologie des Vokabularherausgebers.
- Die im Ausgangskonzept erwähnten Anforderungen an Konfidenz, KI-Modelle und Validierungsstatus wurden **nicht** in verbindliche Property- und Kardinalitätsvorgaben umgedeutet.

## Hinweise zur Nutzung

Apache Jena, Beispiel:

```bash
shacl validate --shapes items_shapes.ttl --data objekt.rdf
```

Wenn SHACL-Regeln die lokalen `ddbv:`-Oberklassen als Targets benutzen, müssen die entsprechenden `*_class_definitions.ttl`-Tripel dem **Datengraphen** zugemischt werden. Die Starter-Shapes zielen überwiegend auf die Originalklassen, damit ein isolierter erster Lauf funktioniert.

Die SHACL-Eingabe validiert den RDF-Graphen. RDF/XML, Turtle und JSON-LD sind lediglich unterschiedliche Serialisierungen.

### Nicht abgedeckt

Das Paket ersetzt nicht Europeanas vollständigen EDM-External-Validator. Die SHACL-Regeln sind absichtlich minimal. Insbesondere fehlen bis zur fachlichen Abstimmung Pflichtangaben für alle vier Bereiche, kontrollierte Wertebereiche außerhalb `edm:type`, mehrteilige Provenienzprüfungen, globale Graph-Konsistenzregeln und fachliche Zuordnung der Datensätze zu den Items.

## Quellen

- DDB-Metadatenkonzept Version 0.1 (06.11.2026)
- Europeana EDM: https://github.com/europeana/metis-edm-ext-schema
- SHACL: https://www.w3.org/TR/shacl/
- DCAT 3: https://www.w3.org/TR/vocab-dcat-3/
- PROV-O: https://www.w3.org/TR/prov-o/
- DQV: https://www.w3.org/TR/vocab-dqv/
