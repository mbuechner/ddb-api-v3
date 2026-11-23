# Logs für Nutzung und Performance

API und Gateway schreiben standardmäßig eine JSON-Zeile pro Log-Ereignis auf
stdout. Spring Boot erzeugt das [Elastic Common Schema (ECS)](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured);
Jetty und Reactor Netty liefern die Daten am Ende der HTTP-Antwort. Dafür sind
keine zusätzliche Bibliothek, kein Request-Body-Puffer und kein MDC nötig.

## Was ein Access-Event enthält

Access-Events haben `event.dataset=http.access` und den Logger
`de.ddb.apiv3.access`. Sie werden ohne Sampling auf `INFO` geschrieben,
einschließlich Fehlern, Redirects und Rate-Limits. Bei `/actuator/health/liveness`
und `/actuator/health/readiness` entfallen in API und Gateway nur Antworten mit
Status `200`, auch mit Query-String. Probe-Fehler wie `503` bleiben sichtbar.

| Feld | Bedeutung |
| --- | --- |
| `@timestamp` | Zeitpunkt des Log-Ereignisses am Ende der Anfrage, mit Zeitzone |
| `service.name` | `ddb-api-v3-gateway` oder `ddb-api-v3-service` |
| `event.duration` | Gesamtdauer in **Nanosekunden**; für Millisekunden durch 1.000.000 teilen |
| `http.request.method` | HTTP-Methode |
| `url.path` | Ressourcenpfad **ohne Query-String** |
| `http.response.status_code` | HTTP-Status als Zahl |
| `http.response.body.bytes` | Vom Server erfasste Antwortbytes, soweit verfügbar |
| `http.request.body.bytes` | Gelesene Body-Bytes, soweit vom Server verfügbar |
| `source.ip`, `source.port` | Tatsächlicher Socket-Peer, ohne Reverse-DNS-Auflösung |
| `client.ip`, `client.port` | Vom nativen Server ermittelte Client-Adresse; standardmäßig ebenfalls der Socket-Peer |
| `http.version`, `network.protocol` | HTTP-Version und Protokoll |
| `labels.client_id` | Syntaktisch geprüfte Client-ID aus dem konfigurierten Header |
| `labels.client_id_source` | `header`: eine Angabe des Aufrufers, keine Authentifizierungsbestätigung |
| `labels.request_content_type`, `labels.response_content_type` | Content-Type-Header für Formatvergleiche |
| `labels.request_content_encoding`, `labels.response_content_encoding` | Kompression, etwa `gzip`; fehlend bedeutet keine deklarierte Kompression |
| `user_agent.original` | Begrenzt gespeicherter User-Agent, hilfreich für Bots/Tools |
| `http.request.id` | Request-ID zur Korrelation, soweit verfügbar; siehe unten |

Status, Dauer und Bytezahlen bleiben numerisch: kein späteres Zerlegen von
Textnachrichten nötig. Die Feldsemantik folgt [ECS Event](https://www.elastic.co/docs/reference/ecs/ecs-event)
und [ECS HTTP](https://www.elastic.co/docs/reference/ecs/ecs-http).
Die Auflösung des Gateway-Timers beträgt eine Millisekunde; die Einheit des
gespeicherten Werts ist trotzdem ECS-konform Nanosekunden.
In der JSON-Ausgabe sind die Felder verschachtelt, beispielsweise
`{"event":{"dataset":"http.access","duration":12500000}}`.

Die Dauer umfasst Verarbeitung, Warteschlangen und das Schreiben der Antwort,
beim Gateway zusätzlich den Backend-Zugriff. Sie misst weder reine CPU-Zeit noch
die vollständige Laufzeit im Browser. Große Antworten und langsame Leser können
daher längere Zeiten verursachen. Bytezahlen sind Servermessungen, keine
TCP-/TLS-Abrechnung; Kompression und die betrachtete Schicht berücksichtigen.

## Request-Korrelation und Teilzeiten

Gateway und API übernehmen eine gültige `X-Request-Id` oder erzeugen eine UUID.
Der Gateway reicht sie zur API durch; Antwort, ProblemDetail und Logs verwenden
dieselbe ID. Zulässig sind 1–128 Zeichen, erstes Zeichen alphanumerisch, danach
zusätzlich `.`, `_`, `:`, `-`. Eine vom Client gelieferte ID dient ausschließlich
der Korrelation und ist kein Identitätsnachweis. Für eigene Messungen pro Anfrage
eine neue ID verwenden; wiederverwendete Request-IDs können sonst zusammenfallen.

Die API ergänzt ihr vorhandenes `http.access`-Event. Der Gateway schreibt neben
seinem nativen Access-Event eine Zusammenfassung mit `event.dataset=http.performance`
und Logger `de.ddb.apiv3.performance`. Diese unterscheidet auch Fehler und
abgebrochene reaktive Verarbeitung. Für Nutzungsstatistiken weiterhin nur
`http.access` zählen; zusätzliche Timing-Events sind keine weiteren Requests.

Alle `performance.*_ms`-Werte sind numerische Millisekunden. Die Zeitmessung nutzt
`System.nanoTime()`, nicht synchronisierte Wanduhren zwischen Pods. Die bereits
vorhandene ECS-`event.duration` bleibt in Nanosekunden. Fehlende Stages wurden
nicht ausgeführt oder nicht erreicht; sie sind nicht als Nullzeit zu interpretieren.

| Komponente / Feld | Gemessener Abschnitt |
| --- | --- |
| Beide: `performance.total_ms`, `performance.slow` | Gesamtdauer und Markierung ab der konfigurierten Schwelle, standardmäßig 100 ms |
| Gateway: `performance.gateway_before_backend_ms` | Bis zum Start des Backend-Aufrufs, einschließlich Security, Routing und Rate-Limit |
| Gateway: `performance.rate_limit_ms` | Bucket4j-Prüfung, im Profil `prod` einschließlich Redis-Kommunikation; im Profil `dev` lokale Zustandsverwaltung |
| Gateway: `performance.backend_headers_ms` | Backend-Aufruf bis zu den Antwortheadern: Pool, DNS/Connect, Anfrageübertragung und API-Verarbeitung zusammen |
| Gateway: `performance.backend_wait_ms` | Bei Fehler/Abbruch vor Backend-Headern: bis dahin verstrichene Wartezeit; keine erfundene Header- oder Transferzeit |
| Gateway: `performance.response_transfer_ms` | Ab Backend-Headern bis zum vollständigen Abschluss, einschließlich Streaming, Client-Backpressure und gegebenenfalls lokaler Fehlerbehandlung |
| API: `performance.capacity_wait_ms` | Eintritt in die Kapazitätsbegrenzung bis Weitergabe; einschließlich Suspend-/Resume-Scheduling, keine Messung sämtlicher Wartezeit vor Jetty |
| API: `performance.application_ms` | Servlet-/Security-/MVC-Verarbeitung einschließlich der nachfolgenden API-Stages |
| API: `performance.s3_read_ms`, `performance.s3_write_ms` | Vollständiger S3-SDK-Aufruf einschließlich Body und Poolwartezeit; automatische SDK-Retries sind derzeit deaktiviert |
| API: `performance.jelly_decode_ms`, `performance.rdf_serialize_ms` | Jelly prüfen/dekodieren und gewünschtes Ausgabeformat erzeugen |
| API bei Uploads: `performance.upload_read_ms`, `performance.rdf_parse_ms`, `performance.jelly_encode_ms` | Body einlesen, RDF/XML validieren/parsen und Jelly für S3 erzeugen |
| API bei Uploads: `performance.shacl_validate_ms` | Die Shapes des jeweiligen Endpunkts auf dem bereits geparsten Graphen prüfen; nur POST/PUT |

**Verschachtelte Zeiten nicht addieren:** Rate-Limit ist Teil von
`gateway_before_backend_ms`, S3 und RDF sind Teil von `application_ms`, die API
liegt wiederum im Backend-Aufruf des Gateways. `backend_headers_ms` ist keine
reine API- oder Netzwerkzeit. Native Accesslogs schließen das serverseitige
Schreiben ein; ein reaktives Summary ist keine Bestätigung, dass der Client alle
Bytes bereits empfangen hat. Für Summen oder Differenzen immer dieselbe Request-ID
vergleichen, nicht voneinander unabhängige p95-Werte subtrahieren.

Die Slow-Schwelle filtert und sampelt keine Events: schnelle Referenzrequests
bleiben für Vergleiche sichtbar. Erfolgreiche Liveness-/Readiness-Probes werden
weiterhin ausgelassen. Die Diagnose fügt keine RDF-Bodies, Tokens oder Querys zum
Log hinzu. Für die konkrete Untersuchung siehe [OpenShift-Latenzdiagnose](latency-diagnostics.md).

## Richtig zählen

**Nutzungsstatistiken aus den Gateway-Events bilden.** Die API sieht dieselbe
Anfrage ein zweites Mal; beide Services zusammenzuzählen verdoppelt Zugriffe.
Für Backend-Performance separat nach `service.name=ddb-api-v3-service` filtern.
Bei direkten API-Aufrufen ohne Gateway stattdessen nur den API-Service auswerten.

Sinnvolle Kennzahlen:

- Anfragen pro Stunde/Tag, Methode, Status, Ausgabeformat und Client-ID.
- Erfolgreiche Datensatzabrufe: `GET`, `/3/records/...`, Status `200`.
  `HEAD`, `304`, Redirects und fehlgeschlagene Abrufe separat zählen.
- Antwortzeit p50/p95/p99 nach Ausgabeformat und Status; Mittelwerte allein
  verbergen langsame Ausreißer. Unterschiedliche Objektgrößen berücksichtigen.
- Fehlerrate `5xx`, abgewiesene Zugriffe `429` und Überlast `503`, jeweils mit
  passendem Nenner aus derselben Menge von Anfragen.
- Übertragene Antwortbytes pro Zeitraum; Health, Swagger und Dokumentation für
  fachliche Nutzungszahlen ausfiltern.

Ein Redirect und sein Ziel sind zwei HTTP-Anfragen. Für fachliche Abrufzahlen
nur das erfolgreiche versionierte Ziel zählen. Header-Client-IDs und IPs sind
keine zuverlässige Anzahl von Personen: IDs können fehlen oder wechseln,
IPs können durch NAT/Proxies geteilt werden. Für Quoten verwendet das Gateway
bei gültigen Tokens den geprüften Principal. `labels.client_id` im Access-Log
bleibt dagegen die angegebene Header-ID, kein Nachweis dieser Identität.

### Beispiel für eine spätere Auswertung

Für ein vorhandenes Elasticsearch mit JSON-Ingestion kann folgende ES|QL-Abfrage
als Ausgangspunkt dienen. `logs-*` durch den tatsächlichen Indexnamen ersetzen.
Sie zählt `GET /3/records/...` der letzten 24 Stunden und berechnet stündliche
Perzentile je tatsächlichem Antwortformat. Ein Logbackend wird mit der Anwendung
nicht installiert.

```esql
FROM logs-*
| WHERE @timestamp >= NOW() - 24 hours AND @timestamp < NOW()
    AND event.dataset == "http.access"
    AND service.name == "ddb-api-v3-gateway"
    AND http.request.method == "GET"
    AND url.path LIKE "/3/records/*"
| EVAL duration_ms = event.duration / 1000000.0,
       hour = DATE_TRUNC(1 hour, @timestamp)
| STATS requests = COUNT(*),
        errors_5xx = COUNT(*) WHERE http.response.status_code >= 500,
        limited_429 = COUNT(*) WHERE http.response.status_code == 429,
        p50_ms = PERCENTILE(duration_ms, 50),
        p95_ms = PERCENTILE(duration_ms, 95),
        p99_ms = PERCENTILE(duration_ms, 99)
    BY hour, output_format = labels.response_content_type
| SORT hour ASC, output_format ASC
| LIMIT 1000
```

Für Zeiten erfolgreicher Abrufe zusätzlich auf Status `200` filtern; Fehler
haben ein eigenes Antwortformat. Stunden werden hier in UTC gebildet und
Perzentile näherungsweise berechnet. Syntax: [STATS mit Filtern](https://www.elastic.co/docs/reference/query-languages/esql/commands/stats-by),
[PERCENTILE](https://www.elastic.co/docs/reference/query-languages/esql/functions-operators/aggregation-functions/percentile)
und [DATE_TRUNC](https://www.elastic.co/docs/reference/query-languages/esql/functions-operators/date-time-functions/date_trunc).
Das Beispiel ist anhand der Dokumentation geprüft, ohne Elasticsearch-Instanz
nicht ausgeführt.

## Betrieb und Konfiguration

```dotenv
DDB_API_LOG_FORMAT=ecs
DDB_API_ACCESS_LOG_ENABLED=true
DDB_API_ACCESS_LOG_LEVEL=INFO
DDB_API_PERFORMANCE_LOG_ENABLED=true
DDB_API_SLOW_REQUEST_THRESHOLD=100ms
DDB_API_CLIENT_ID_HEADER_NAME=X-Client-Id
DDB_API_FORWARD_HEADERS_STRATEGY=none
DDB_API_FORWARD_HEADERS_NAMING=rfc-7239

GATEWAY_LOG_FORMAT=ecs
GATEWAY_ACCESS_LOG_ENABLED=true
GATEWAY_ACCESS_LOG_LEVEL=INFO
GATEWAY_PERFORMANCE_LOG_ENABLED=true
GATEWAY_SLOW_REQUEST_THRESHOLD=100ms
GATEWAY_CLIENT_ID_HEADER_NAME=X-Client-Id
GATEWAY_FORWARD_HEADERS_STRATEGY=none
GATEWAY_FORWARD_HEADERS_NAMING=rfc-7239
```

Die Einstellungen sind je Anwendung getrennt und werden von Compose
weitergereicht. Für dieselbe protokollierte Header-ID an beiden Hops die beiden
`*_CLIENT_ID_HEADER_NAME`-Werte aufeinander abstimmen.
Der Helm-Chart aktiviert ECS und Access-Logs standardmäßig;
`logging.accessEnabled: false` schaltet Access-Events gezielt ab und macht
vollständige Nutzungsstatistiken für diesen Zeitraum unmöglich. Das Access-Level
ist separat auf `INFO` gesetzt, damit ein allgemeines `WARN` die Statistik nicht
versehentlich abschaltet. Ein eigenes höheres Access-Level unterdrückt sie trotzdem.
`logging.performanceEnabled` und `logging.slowRequestThreshold` steuern im Chart
die Teilzeiten beider Anwendungen. Mit `*_PERFORMANCE_LOG_ENABLED=false` entfällt
die jeweilige Timing-Diagnose; die Request-ID-Korrelation bleibt erhalten. Bei
der API läuft die Erfassung nur bei aktiven Accesslogs auf `INFO`; die Entscheidung
wird pro Anfrage beim Eintritt getroffen. Gateway-Timing-Events lassen sich
unabhängig von seinen Accesslogs abschalten. Aktivierte Gateway-Performance-
Diagnose schaltet zusätzlich Verbindungspool-Metriken ein (`httpclient.pool.metrics`);
der Performance-Schalter deaktiviert auch diese zusätzliche Instrumentierung.

Im Cluster sammelt der vorhandene Log-Agent stdout ein und ergänzt Namespace,
Pod und Container. Keine Logdateien im Container, kein zusätzliches Volume und
kein direkter Zugriff auf Elasticsearch/Loki im Anfragepfad. Sammlung,
Aufbewahrung, Zugriffsschutz und Rotation übernimmt der Betrieb gemäß dem
[Kubernetes-Logging-Modell](https://kubernetes.io/docs/concepts/cluster-administration/logging/).
JSON-Zeilen genau einmal dekodieren und Zeit-/Zahlenfelder typisiert übernehmen.
Client-IDs, IPs und Objektpfade gehören in Logs, nicht in Metrik-Labels mit
unbegrenzter Kardinalität.

## Grenzen und Schutz vor Datenmüll

Es werden keine RDF-Bodies, Query-Strings, Authorization-Header, Cookies oder
vollständigen Header-Sammlungen protokolliert. Client-IDs sind auf 128 Zeichen
begrenzt und werden nur bei gültiger Syntax übernommen. Weitere gespeicherte
Header sind begrenzt; die JSON-Kodierung übernimmt Spring Boot. Das folgt den
[OWASP-Empfehlungen zum Logging](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html).

`source.ip` ist bewusst die physische Gegenstelle. Hinter einem Ingress ist das
oft dessen IP; der API-Service sieht typischerweise das Gateway. Standardmäßig
gelten `DDB_API_FORWARD_HEADERS_STRATEGY=none` und
`GATEWAY_FORWARD_HEADERS_STRATEGY=none`, auch im Cluster.
Beliebige `Forwarded`-/`X-Forwarded-For`-Werte verändern deshalb `client.ip` nicht.

Nur wenn ausschließlich ein vertrauenswürdiger Ingress das Gateway erreicht
und eingehende Forwarded-Header entfernt und selbst korrekt setzt, kann dessen
native Auswertung eingeschaltet werden. Seit Spring Boot 4.2 wertet jede
Anwendung eine Header-Familie aus. Standard ist `Forwarded` nach RFC 7239.
Bei bestehenden Installationen mit aktivierter Auswertung und
`X-Forwarded-*` muss vor dem Upgrade das jeweilige
`*_FORWARD_HEADERS_NAMING` auf `x-forwarded` gesetzt werden. Ohne diese
Einstellung verwendet das Gateway bei der anonymen Ratenbegrenzung die IP
des Ingress für dessen Anfragen.

```yaml
gateway:
  forwardHeadersStrategy: native
  extraEnv:
    GATEWAY_FORWARD_HEADERS_NAMING: x-forwarded
```

Dann bleibt `source.ip` die Ingress-IP und `client.ip` enthält die vom Server
ausgewertete ursprüngliche Client-IP. Dafür gibt es keinen eigenen Header-Parser.
Die native Einstellung beeinflusst ebenfalls Schema/Host der Anfrage; mit der
RDF-Basis-Konfiguration gemeinsam abstimmen. Die Spring-Strategie `framework`
wirkt erst oberhalb dieser Server-Callbacks und ist für die ursprüngliche IP
im nativen Access-Log nicht geeignet. Alternativ die externe IP im Collector aus
dem vertrauenswürdigen Ingress-Log ergänzen.

`X-Request-Id` verbindet Gateway-, API- und Fehlerlogs; dies aktiviert kein
verteiltes W3C-Tracing. Native Server-IDs bleiben nur ein Fallback für Anfragen,
die die normale Verarbeitung nicht erreichen.

Die nativen Callbacks erfassen abgeschlossene HTTP-Antworten. Verbindungsfehler
vor der Anfrage und insbesondere abgebrochene Netty-Antworten erscheinen nicht
als normale Access-Events. Ingress-/Serverfehlerlogs ergänzend behalten.
Kein Sampling bedeutet auch nicht revisionssichere Zustellung: Containerabstürze
oder eine ausgefallene Log-Pipeline können Daten verlieren. Die stdout-Ausgabe
ist synchron; ein langsamer Log-Abnehmer erzeugt Backpressure. Unter echter
Produktionslast mit eingeschaltetem Logging messen.
Die Gateway-Diagnose schreibt eine zusätzliche JSON-Zeile je Anfrage; auch die
API-Zeile enthält zusätzliche Felder. Timer, Allokationen, JSON-Kodierung und
Log-Ingestion sind daher nicht kostenlos. Die Implementierung fügt weder
Body-Puffer noch Scheduler-Wechsel oder zusätzliche Backend-Aufrufe für die
Messung hinzu. Bei abgeschalteter API-Diagnose wird keine Stage-Map angelegt.
