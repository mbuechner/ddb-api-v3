# Zusätzliche Latenz unter OpenShift eingrenzen

Öffentliche Route und `localhost` vergleichen unterschiedliche Wege. Die
Differenz enthält Client-Netz, DNS, TCP/TLS und Router sowie andere Ressourcen,
S3- und Rate-Limit-Backends. Sie ist nicht automatisch Gateway-Overhead. Zuerst
den Weg aufteilen, dann eine einzelne Hypothese verändern.

## Identische Requests kalt und warm messen

Dasselbe Image, denselben Record, dasselbe Ausgabeformat und dieselbe Kompression
verwenden. Lokales `dev` nutzt einen lokalen Rate-Limiter, Cluster-`prod` Redis;
dieser Unterschied bleibt ohne gesonderten Vergleich bestehen. Zunächst seriell
messen, erst danach mit definierter Parallelität. Probes sind keine repräsentative
RDF-Last; `304` umgeht die Serialisierung, `429` und `503` getrennt auswerten.

Beispiel für Bash mit curl ab 7.84; unter PowerShell `curl.exe` und `NUL` statt
`/dev/null` verwenden. Vier Transfers im **selben** Prozess erlauben Keep-alive;
vier getrennte curl-Prozesse würden jeweils neu verbinden.

```bash
url='https://api-v3.deutsche-digitale-bibliothek.de/3/records/J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3'
curl --silent --show-error --compressed --connect-timeout 3 --max-time 15 \
  --rate 2/s -H 'Accept: application/ld+json' \
  -w 'status=%{http_code} id=%header{x-request-id} dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect} ready=%{time_pretransfer} ttfb=%{time_starttransfer} total=%{time_total} new_connections=%{num_connects}\n' \
  -o /dev/null "$url" -o /dev/null "$url" \
  -o /dev/null "$url" -o /dev/null "$url"
```

`--rate` erfordert curl ab 7.84. Diese kleine Serie dient dem Warm-up-Vergleich,
nicht einer belastbaren p95-Auswertung. Danach genügend erfolgreiche warme
Samples bei einer Rate unterhalb der konfigurierten Quote sammeln.

Die [curl-Zeiten](https://curl.se/docs/manpage.html#-w) sind Sekunden seit
Transferbeginn und **kumulativ**: `tls - connect` isoliert bei einer neuen direkten
HTTPS-Verbindung näherungsweise den TLS-Aufbau. `ttfb - ready` enthält weiterhin
Netzweg, Router und Server. Bei wiederverwendeten Verbindungen können DNS-,
Connect- und TLS-Werte Null sein; `new_connections=0` bestätigt die Wiederverwendung.
Proxies und HTTP-Version mit berücksichtigen; nicht TLS-Prüfungen deaktivieren.

## Drei Wege trennen und Logs korrelieren

Mit demselben curl-Aufruf aus einem vorhandenen, zugelassenen Diagnose-Pod im
Namespace nacheinander messen; Service-Namen gegebenenfalls anpassen:

1. `http://ddb-api-v3-api-service:8080/3/records/...`: API und S3/RDF, ohne Gateway.
2. `http://ddb-api-v3-gateway:8081/3/records/...`: zusätzlich Gateway und Redis.
3. Öffentliche HTTPS-Route, außerdem vom eigentlichen Client aus: zusätzlich
   Router/TLS/externes Netz. Ein Cluster-Aufruf über die öffentliche Route kann
   anders geroutet werden als der externe Client und ersetzt diesen Vergleich nicht.

Falls kein Diagnose-Pod zur Verfügung steht, kann `oc port-forward` ergänzend
helfen; dessen Tunnel ist aber eine weitere Messschicht und kein neutraler
Netzwerk-Benchmark. Firewall-/NetworkPolicy-Vorgaben weiter beachten.

Die in curl ausgegebene ID in **beiden** Pod-Logs suchen. Bei mehreren Replikaten
alle passenden Pods einbeziehen, zum Beispiel für den Standard-Release:

```bash
oc logs -n ddb-api-v3 -l app.kubernetes.io/instance=ddb-api-v3 \
  --all-containers=true --prefix=true --since=10m --tail=-1 --max-log-requests=10 | grep -F '<request-id>'
```

Für strukturierte Auswertung direkt JSON vom Collector verwenden; `--prefix=true`
fügt CLI-Metadaten hinzu und ist hier nur zur manuellen Suche gedacht. Messungen
mit wiederverwendeter Request-ID, Redirects oder Retries können mehrere Events pro ID
haben. Zeitdauern vergleichen, nicht Host-Zeitstempel voneinander subtrahieren.

| Beobachtung bei derselben Anfrage | Nächster Nachweis |
| --- | --- |
| Client langsam, Gateway/API schnell | Cold/warm und internen Weg vergleichen; RTT, TLS und Routerzugriffslog untersuchen |
| `rate_limit_ms` groß | Redis-CPU und Netzweg prüfen; der CAS-Limiter kann bei Konkurrenz zusätzliche Roundtrips benötigen |
| `backend_headers_ms` groß, API-Dauer klein | Gateway-Verbindungspool, DNS/Connect und internen Transport prüfen; Pool-Pending-Metriken am vorhandenen Gateway-Prometheus-Endpunkt ansehen |
| `capacity_wait_ms` groß | Aktive RDF-Arbeit, Parallelität und API-Limits prüfen; mehr Parallelität vergrößert bei CPU-Mangel nur die Warteschlange |
| `s3_read_ms` groß | S3-Pfad/Backend und Pool prüfen; die Zeit allein trennt diese Ursachen noch nicht. Der aktuelle S3-Client wiederholt SDK-Anfragen nicht automatisch |
| `jelly_decode_ms` / `rdf_serialize_ms` groß | Ausgabeformate vergleichen und CPU-Drosselung bzw. GC überprüfen |
| `response_transfer_ms` groß | Antwortgröße, Kompression, Client-Empfang und Netzwerk-Backpressure vergleichen |

## CPU-Drosselung gezielt nachweisen

Der Chart begrenzt standardmäßig die API auf `500m`, Gateway und Redis jeweils
auf `200m` CPU. Entscheidend sind jedoch die **laufenden** Pods einschließlich
`LimitRange`-Vorgaben. [Kubernetes erzwingt CPU-Limits durch Throttling](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/).
Bei erschöpftem Budget pausieren Threads bis zur nächsten Budgetperiode; Linux
verwendet klassisch eine [100-ms-Periode](https://www.kernel.org/doc/html/v6.8/scheduler/sched-bwc.html).
Ein Aufschlag in dieser Größenordnung ist ein Indiz, kein Beweis. Kurze Bursts
können drosseln, obwohl die über Minuten gemittelte CPU-Auslastung niedrig wirkt.

```bash
oc get pods -n ddb-api-v3
oc get limitrange,resourcequota -n ddb-api-v3
oc get pod -n ddb-api-v3 <pod> -o jsonpath='{.spec.containers[*].resources}'
oc adm top pods -n ddb-api-v3 --containers
# cgroup v2: vor und nach derselben Messserie lesen, nicht nur den Gesamtstand.
oc exec -n ddb-api-v3 <pod> -c <container> -- cat /sys/fs/cgroup/cpu.max
oc exec -n ddb-api-v3 <pod> -c <container> -- cat /sys/fs/cgroup/cpu.stat
```

`nr_throttled` und `throttled_usec` sind kumulative Zähler. Die Deltas während der
Serie mit Latenzen vergleichen, getrennt für API, Gateway und Redis. Je nach
Cgroup-Mount ist der Pfad anders; bei v1 liegen die Dateien typischerweise unter
`/sys/fs/cgroup/cpu/` (`cpu.cfs_quota_us`, `cpu.cfs_period_us`, `cpu.stat`, dort
`throttled_time` in Nanosekunden). `oc adm top` allein belegt keine Drosselung.

Wenn der Cluster diese cAdvisor-Metriken bereitstellt, zeigt folgende PromQL den
Anteil der gedrosselten CPU-Perioden; Namespace und Zeitraum anpassen:

```promql
100 * sum by (pod, container) (
  rate(container_cpu_cfs_throttled_periods_total{namespace="ddb-api-v3",container!="",container!="POD"}[5m])
) / sum by (pod, container) (
  rate(container_cpu_cfs_periods_total{namespace="ddb-api-v3",container!="",container!="POD"}[5m])
)
```

Das ist weder verlorene CPU-Prozentzeit noch ein einzelner Request-Timer.
Fehlende Metriken bedeuten nicht Null-Throttling. Export, Berechtigungen und
Labels sind clusterabhängig; Cgroup-Deltas dienen als zweite Messquelle.

Zur Bestätigung in einer Testinstallation **ein** CPU-Limit anheben, denselben
Warm-up und dieselbe Last wiederholen und anschließend den Ausgangswert erneut
messen (A/B/A). Requests, RAM, Image und Replikazahl dabei konstant halten, soweit
Cluster-Quoten das erlauben. Sinkende Stage-Zeiten zusammen mit weniger Throttling
stützen die Hypothese. Änderungen hier sind ein Versuchsplan, keine automatisch
ausgeführte Ressourcenanpassung. Bei unauffälliger CPU folgen S3/Redis-Netzweg,
Poolwartezeiten und GC; niedriges RAM kann durch häufigere GC zusätzlich CPU
verbrauchen. Die Anwendung bietet hier noch keine Einzelmessung für GC, DNS oder
die Wartezeit im S3-Pool. VPA im Modus `Off` liefert Empfehlungen und beseitigt das
Throttling nicht selbst.
