# Entwicklungskonventionen

`api-service/src/main/openapi/openapi.yaml` ist die einzige Quelle für Endpunkte,
DTOs und Validierungsregeln. `target/generated-sources/openapi` wird generiert,
nicht manuell geändert und nicht eingecheckt.

Handgeschriebener Code bleibt auf fachliche Abbildung und unvermeidbare Adapter
begrenzt. Bevor eine eigene Komponente entsteht, werden Spring-Boot-
Autokonfiguration, Spring Data beziehungsweise Spring Cloud Gateway verwendet.

Schnelle Tests laufen mit dem Profil `dev` gegen H2. Nur der Cassandra-
Integrationstest startet Cassandra per Testcontainers und aktiviert dafür das
Profil `prod`. Gateway-Integrationstests verwenden bei verfügbarem Docker einen
Redis-Testcontainer. Die CI muss übersprungene Container-Tests sichtbar machen.

Java Records ersetzen DTO-Boilerplate. Lombok wird nicht eingebunden: In den
verbleibenden Klassen würde es nur wenige Konstruktorzeilen sparen, aber einen
zusätzlichen Annotation Processor und eine weitere Build-Abhängigkeit einführen.

Die handgeschriebene Record-Implementierung folgt den Ressourcen der API:

```text
record/
├── api/          HTTP-Delegate für die generierten Mappings
├── metadata/     GET der administrativen DCAT-Metadaten
├── content/      GET/HEAD des RDF-Inhalts und Jena-Konvertierung
├── persistence/  technologieunabhängige Grenze
│   ├── h2/       lokaler Adapter
│   └── cassandra/ produktiver Adapter
└── StoredRecord* kleine persistenznahe Domain-Werte
```

Neue Endpunkte erhalten nach demselben Muster ein fachliches Unterpaket. Eine
zusätzliche Schicht entsteht nur, wenn sie Datenzugriff oder wiederverwendbares
Verhalten tatsächlich abgrenzt.

Versionen werden vom Spring-Boot- beziehungsweise Spring-Cloud-BOM verwaltet.
Nach Änderungen muss `mvn clean verify` erfolgreich sein; generierter Code wird
zusätzlich mit Spotless von unbenutzten Imports bereinigt.
