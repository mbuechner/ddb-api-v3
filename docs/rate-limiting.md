# Rate Limiting

Nur die Route `/3/**` ist begrenzt. Alle Profile verwenden dieselbe Route und
Spring Cloud Gateways `Bucket4jRateLimiter`. Lediglich der Zustandsspeicher ist
anders: `dev` verwendet einen prozesslokalen Caffeine-Cache, `prod` verwendet
Redis über Lettuce. Im Helm-Deployment aktivieren Spring Boots native
`SPRING_DATA_REDIS_SENTINEL_*`-Variablen die Primary-Ermittlung über Sentinel;
ein zusätzliches Anwendungsprofil ist dafür nicht nötig.

Für beide Varianten gelten:

| Variable | Standard |
| --- | ---: |
| `RATE_LIMIT_REPLENISH_RATE` | 10 |
| `RATE_LIMIT_REFILL_PERIOD` | 1s |
| `RATE_LIMIT_BURST_CAPACITY` | 20 |
| `RATE_LIMIT_REQUESTED_TOKENS` | 1 |

Ein Request verbraucht `RATE_LIMIT_REQUESTED_TOKENS`. Der Bucket fasst höchstens
`RATE_LIMIT_BURST_CAPACITY` Token und erhält pro
`RATE_LIMIT_REFILL_PERIOD` wieder `RATE_LIMIT_REPLENISH_RATE` Token. Reichen die
Token nicht, antwortet das Gateway selbst mit `429 Too Many Requests` und leerem
Body.

## Bucket-Schlüssel

Ein authentifizierter Principal hat immer Vorrang. `dev` erlaubt
danach den validierten Request-Header `X-Client-Id`, andernfalls die direkte
Client-IP. `prod` akzeptiert standardmäßig nur den Principal. Fehlt dort ein
Schlüssel, antwortet Spring Cloud Gateway mit `403 Forbidden`, noch ohne
Rate-Limit-Header.

## Response-Header

Bei einer zugelassenen Anfrage liefert das Gateway:

| Header | Inhalt |
| --- | --- |
| `X-RateLimit-Remaining` | nach der Anfrage verbleibende Token |
| `X-RateLimit-Replenish-Rate` | Token je Auffüllintervall |
| `X-RateLimit-Burst-Capacity` | maximale Bucket-Größe |
| `X-RateLimit-Requested-Tokens` | von dieser Anfrage verbrauchte Token |

Spring Cloud Gateway 5.0.3 erzeugt mit `Bucket4jRateLimiter` selbst nur
`X-RateLimit-Remaining`. Drei eingebaute `SetResponseHeader`-Filter ergänzen die
konstanten Routenwerte bei erfolgreichen Antworten; eigener Java-Code ist dafür
nicht notwendig. Der explizit konfigurierte
`spring.cloud.gateway.server.webflux.redis-rate-limiter`-Block verwendet den
Property-Präfix von Gateway 5.x und hält denselben Headervertrag fest, wird aber
vom ausgewählten Bucket4j-Limiter nicht ausgewertet.

Bei einer Ablehnung mit 429 setzt Bucket4j `X-RateLimit-Remaining` auf den
unveränderten Rest. `RequestRateLimiter` beendet die Filterkette an dieser
Stelle, daher laufen die drei nachgelagerten `SetResponseHeader`-Filter nicht
mehr. Alle vier Header auch bei 429 wären mit Bucket4j 5.0.3 nur durch eigenen
Java-Code erreichbar. Browser dürfen alle vier Rate-Limit-Header und
`Retry-After` über CORS lesen.

Dokumentationsrouten, Actuator-Endpunkte und vom globalen CORS-Handler
beantwortete Preflight-Requests durchlaufen den Filter nicht und erhalten diese
Header nicht. Ist Redis bereits beim Start nicht erreichbar, startet das
`prod`-Gateway nicht. Scheitert das Backend erst während des Betriebs,
weist Bucket4j den Request mit 429 und `X-RateLimit-Remaining: -1` ab.
`Retry-After` oder standardisierte `RateLimit-*`-Header werden derzeit nicht
gesetzt.

Die Token-Bucket-Logik stammt aus Spring Cloud Gateway; eigener Rate-Limiter-Code
existiert nicht. Lokale Buckets werden nicht zwischen Prozessen geteilt und gehen
beim Neustart verloren.

Referenz: [Spring Cloud Gateway RequestRateLimiter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html)
