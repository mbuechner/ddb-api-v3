# HTTP-Vertrag

[openapi.yaml](openapi.yaml): GET/HEAD öffentlich, POST/PUT mit Bearer-Token.
Pfade: `/3/records/{recordId}` und `/3/items/{itemId}`. Input: RDF/XML.
Output: JSON-LD, RDF/XML, Jelly RDF. Writes benötigen den jeweiligen Scope
`records:write` beziehungsweise `items:write` und durchlaufen getrennte SHACL-Shapes.
Der Vertrag beschreibt HTTP-Verhalten, keine Implementierungs- oder ENV-Details.

Auslieferung: `/openapi/openapi.yaml`. Swagger: `/swagger-ui.html`;
interaktive Aufrufe verwenden den aktuellen Server. `/3` steht im Operationspfad.

Vom Repository-Root:

```bash
mvn -pl api-service validate
mvn clean verify
```

Maven validiert und paketiert den Vertrag für den API-Service.

[Auth/Tokens](../../../../docs/security.md) · [RDF/IRIs/ETags](../../../../docs/rdf-conventions.md) ·
[Start/Betrieb](../../../../README.md) · [Implementierung](../../../../docs/architecture.md)
