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
LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
DDB_ACCESS_LOG_ENABLED=true
LOGGING_LEVEL_DE_DDB_APIV3_ACCESS=INFO
CLIENT_ID_HEADER_NAME=X-Client-Id
SERVER_FORWARD_HEADERS_STRATEGY=none
```

Die Einstellungen gelten für beide Anwendungen und werden von Compose
weitergereicht. Der Helm-Chart aktiviert ECS und Access-Logs standardmäßig;
`logging.accessEnabled: false` schaltet Access-Events gezielt ab und macht
vollständige Nutzungsstatistiken für diesen Zeitraum unmöglich. Das Access-Level
ist separat auf `INFO` gesetzt, damit ein allgemeines `WARN` die Statistik nicht
versehentlich abschaltet. Ein eigenes höheres Access-Level unterdrückt sie trotzdem.

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
gilt ausdrücklich `SERVER_FORWARD_HEADERS_STRATEGY=none`, auch im Cluster.
Beliebige `Forwarded`-/`X-Forwarded-For`-Werte verändern deshalb `client.ip` nicht.

Nur wenn ausschließlich ein vertrauenswürdiger Ingress das Gateway erreicht
und eingehende Forwarded-Header entfernt und selbst korrekt setzt, kann dessen
native Auswertung eingeschaltet werden:

```yaml
gateway:
  extraEnv:
    SERVER_FORWARD_HEADERS_STRATEGY: native
```

Dann bleibt `source.ip` die Ingress-IP und `client.ip` enthält die vom Server
ausgewertete ursprüngliche Client-IP. Dafür gibt es keinen eigenen Header-Parser.
Die native Einstellung beeinflusst ebenfalls Schema/Host der Anfrage; mit der
RDF-Basis-Konfiguration gemeinsam abstimmen. Die Spring-Strategie `framework`
wirkt erst oberhalb dieser Server-Callbacks und ist für die ursprüngliche IP
im nativen Access-Log nicht geeignet. Alternativ die externe IP im Collector aus
dem vertrauenswürdigen Ingress-Log ergänzen.

Bestehende Fehlerantworten liefern `X-Request-Id`; Access- und Fehlerlog
übernehmen diese ID. Native Server-IDs sind nur für den jeweiligen Hop gedacht,
keine verteilten Trace-IDs. W3C-Tracing wird durch Access-Logging nicht aktiviert.

Die nativen Callbacks erfassen abgeschlossene HTTP-Antworten. Verbindungsfehler
vor der Anfrage und insbesondere abgebrochene Netty-Antworten erscheinen nicht
als normale Access-Events. Ingress-/Serverfehlerlogs ergänzend behalten.
Kein Sampling bedeutet auch nicht revisionssichere Zustellung: Containerabstürze
oder eine ausgefallene Log-Pipeline können Daten verlieren. Die stdout-Ausgabe
ist synchron; ein langsamer Log-Abnehmer erzeugt Backpressure. Unter echter
Produktionslast mit eingeschaltetem Logging messen.
