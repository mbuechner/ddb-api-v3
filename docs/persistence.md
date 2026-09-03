# Persistenz

Es gibt genau zwei Betriebsprofile:

| Profil | Datenbank | Zweck |
| --- | --- | --- |
| `dev` | dateibasiertes, eingebettetes H2 | Entwicklung und manuelle Tests |
| `prod` | externe Cassandra | Produktion und Cassandra-Integrationstest |

Spring Boot konfiguriert lokal H2, HikariCP, `JdbcClient` und die SQL-
Initialisierung. Die Datenbank liegt standardmäßig unter
`./data/ddb-api-v3.mv.db`; `H2_DATABASE_PATH` ändert den Pfad. Das Compose-
Volume `h2-data` bewahrt sie über Container-Neustarts und Neuerstellungen hinweg.
`db/h2/schema.sql` und `db/h2/data.sql` sind idempotent: Sie legen das Schema und den
deterministischen Beispieldatensatz nur an, wenn diese noch fehlen. Eigene Daten
werden bei einem Neustart nicht verändert. Der Adapter enthält je eine Point-
Lookup-Abfrage für Metadaten und für den separat gelesenen RDF/XML-BLOB.

Beim direkten lokalen Maven-Start ist die nur lokal gebundene H2-Konsole unter
`http://localhost:8080/h2-console` aktiv. Im Compose-Betrieb ist sie deaktiviert
und der API-Service-Port wird nicht am Host veröffentlicht. Tests setzen explizit
eine flüchtige In-Memory-URL, damit sie die Entwicklungsdatenbank nie verändern.

Im Produktionsprofil ist die JDBC-Autokonfiguration deaktiviert. Spring Boot
erzeugt eine langlebige `CqlSession`; `CassandraRecordRepository` verwendet
vorbereitete Point-Lookups und atomare Compare-and-set-Schreibzugriffe auf dem
vollständigen Partition Key `record_id`.

Das Referenzschema liegt unter
[`api-service/src/main/resources/db/cassandra/schema.cql`](../api-service/src/main/resources/db/cassandra/schema.cql).
Es wird absichtlich nicht beim Anwendungsstart ausgeführt
(`spring.cassandra.schema-action=none`), sondern separat durch den
Datenbankbetrieb angewendet. Vorher müssen Datacenter-Name, Replikationsfaktor
und gegebenenfalls der Keyspace an die reale Topologie angepasst werden. Der
Integrationstest verwendet exakt dieselbe Datei und prüft damit, dass CQL und
Repository-Modell zusammenpassen.

Beide Adapter lesen dasselbe hostneutrale JSON-Modell. Öffentliche JSON-LD-IRIs
werden erst am HTTP-Rand erzeugt und nicht in H2 oder Cassandra gespeichert.

Der Cassandra-Test nutzt das Produktionsprofil mit einem einzelnen Testcontainer
und reduziert die Konsistenz dafür auf `LOCAL_ONE`. Er prüft CQL, Mapping und
Schreibbedingungen, aber nicht Replikation, Rebalancing, Failover oder
Multi-Datacenter-Verhalten. Diese Eigenschaften müssen in einer
produktionsnahen Umgebung getestet werden.
