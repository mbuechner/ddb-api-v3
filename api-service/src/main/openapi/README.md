# OpenAPI-Kopie für die Implementierung

Die maßgebliche Spezifikation wird im eigenständigen Projekt `apiv3-spec` unter
`openapi/openapi.yaml` gepflegt. Diese lokale Kopie hält Codegenerierung, Tests,
Container-Build und Swagger UI unabhängig von einem zweiten Checkout.

Nach einer Vertragsänderung aus dem Wurzelverzeichnis von `apiv3-impl` kopieren
und prüfen:

```bash
cp ../apiv3-spec/openapi/openapi.yaml api-service/src/main/openapi/openapi.yaml
mvn clean verify
```

Unter PowerShell kann statt `cp` auch `Copy-Item` verwendet werden.
Die aktualisierte Kopie zusammen mit den dazugehörigen Implementierungsänderungen
einchecken. Änderungen am Vertrag zuerst in `apiv3-spec` vornehmen.

`templates/` enthält implementierungsspezifische Java-Generatorvorlagen und
bleibt deshalb hier. Die [API-Dokumentation](../../../../../apiv3-spec/README.md)
liegt im Spezifikationsprojekt.
