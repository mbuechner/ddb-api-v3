# Zugang und Schreibschutz

`POST` und `PUT` für `/3/records/{recordId}` und `/3/items/{itemId}` benötigen
ein Bearer-Token, auch direkt am API-Service und unter den versionlosen Aliasen
`/records/{recordId}` und `/items/{itemId}`. Records verlangen `records:write`,
Items `items:write`; kein Scope erteilt Rechte auf den jeweils anderen Endpunkt.
Alle anderen schreibenden Pfade bleiben gesperrt und benötigen bei Erweiterungen
eine ausdrücklich ergänzte Berechtigungsregel.
Gateway und API prüfen unabhängig,
bevor der RDF-Body verarbeitet oder S3 beschrieben wird. Jettys begrenzte
Warteschlange liegt weiterhin vor der Servlet-Sicherheitsprüfung.
Öffentliche `GET`-/`HEAD`-Zugriffe und CORS-Preflights benötigen kein Token.

| Situation | Ergebnis |
| --- | --- |
| Keine Zugangsdaten konfiguriert | Lesen möglich, Schreiben gesperrt |
| Fehlendes oder ungültiges Bearer-Token beim Schreiben | `401` mit `WWW-Authenticate: Bearer` |
| Gültiges OAuth2-Token ohne den zum Endpunkt passenden Schreib-Scope | `403` |
| Gültiges Token mit Schreibrecht | Normale RDF-/ETag-Prüfung und Speicherung |

Keine Sessions, kein Login-Formular und keine Cookie-Authentifizierung: Die
Prüfung verwendet Spring Security Resource Server. Die CSRF-Prüfung ist für
diesen ausschließlich über den Authorization-Header authentifizierten Zugang
deaktiviert. Ein `X-Client-Id`-Header allein gibt niemals Schreibrechte.

## Lokal ohne Identity Provider

Nur für `APP_PROFILE=dev` kann `DDB_DEV_WRITE_TOKEN` gesetzt werden. Ein leeres
Token sperrt Schreibzugriffe. Benötigt wird ein selbst erzeugtes, kanonisch
Base64URL-kodiertes Token aus **32 kryptografisch zufälligen Bytes**: 43 Zeichen
ohne Padding. Kein Passwort, Beispielwert oder ausgedachter Text.

PowerShell 5 und neuer, ohne Ausgabe des Tokens:

```powershell
$tokenBytes = New-Object byte[] 32
$tokenGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $tokenGenerator.GetBytes($tokenBytes) }
finally { $tokenGenerator.Dispose() }
$encodedToken = [Convert]::ToBase64String($tokenBytes)
$env:DDB_DEV_WRITE_TOKEN = $encodedToken.TrimEnd('=').Replace('+', '-').Replace('/', '_')
```

Bash mit OpenSSL:

```bash
export DDB_DEV_WRITE_TOKEN="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\r\n')"
```

Danach beide Anwendungen aus dieser Shell starten. Alternativ den erzeugten
Wert in der privaten, nicht eingecheckten `.env` bei `DDB_DEV_WRITE_TOKEN=`
eintragen; gesetzte Prozessvariablen haben Vorrang vor der Datei. Gateway und
API benötigen denselben Wert. Es wird kein Token vom Server erzeugt oder
ausgegeben. Tokenvergleich und Bearer-Verarbeitung laufen über einen kleinen
lokalen Introspector und die native Spring-Security-Infrastruktur.

VS Code: Eine geänderte `.env` ersetzt keine bereits geerbten
Prozessvariablen. Bereits offene Terminals können ältere Werte behalten.
Für Java eine Startkonfiguration mit `envFile: "${workspaceFolder}/.env"`,
`cwd: "${workspaceFolder}"` und `console: "internalConsole"` verwenden;
die interne Konsole vermeidet zusätzliche PowerShell-Profile und Startskripte.
Bei widersprüchlichen Werten VS Code samt Terminals vollständig neu starten.

Das Entwicklungstoken ist ein gemeinsamer Schlüssel mit Schreibrecht auf alle
Records und Items. Es hat keine automatische Ablaufzeit und keine individuellen Nutzer.
Zum Rotieren ein neues Token erzeugen und **beide Anwendungen neu starten**.
Bei gleichzeitigem JWT-Vertrauen oder aktivem `prod`-Profil verweigert die
Anwendung den Start mit gesetztem Entwicklungstoken; es gibt keinen
Produktions-Fallback auf dieses Verfahren.

Beispiel in Bash; für ein OAuth2-Access-Token dieselben Header verwenden:

```bash
curl -i -X POST \
  http://localhost:8080/3/records/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA \
  -H "Authorization: Bearer $DDB_DEV_WRITE_TOKEN" \
  -H 'Content-Type: application/rdf+xml' \
  --data-binary @record.rdf

curl -i -X PUT \
  http://localhost:8080/3/records/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA \
  -H "Authorization: Bearer $DDB_DEV_WRITE_TOKEN" \
  -H 'Content-Type: application/rdf+xml' \
  -H 'If-Match: "ETag-aus-der-vorherigen-Antwort"' \
  --data-binary @record.rdf
```

Für Items dieselben Methoden unter `/3/items/ID` mit dem Scope `items:write`
verwenden. Den starken Validator für folgende PUTs aus `ETag` der POST-Antwort
oder aus `X-Item-ETag` einer GET-/PUT-Antwort übernehmen.
Das lokale Entwicklungstoken besitzt beide Schreibrechte.

Swagger: **Authorize** öffnen und nur den Tokenwert einfügen. Den Präfix
`Bearer ` setzt Swagger selbst. Ein gespeichertes Token im gemeinsam benutzten
Browser anschließend über **Logout** im Authorize-Dialog entfernen.

## Produktion mit OAuth2/OIDC

Der Identity Provider vergibt kurzlebige, signierte Access-Tokens. Beide
Anwendungen prüfen sie lokal mit den öffentlichen Schlüsseln des Anbieters:
Signatur, Issuer, API-Audience, verpflichtendes Ablaufdatum, nichtleeres `sub`
und den zum Endpunkt passenden Scope (`records:write` oder `items:write`). Ein
vorhandenes `nbf` wird ebenfalls geprüft.
Bei einem abweichenden nativen `principal-claim-name` muss auch dieser Claim
als nichtleerer String vorhanden sein; er wird als geprüfte Quoten-Identität verwendet.
Die native Uhrzeittoleranz beträgt 60 Sekunden; Serveruhren synchron halten.
Standardalgorithmus ist RS256. Es wird kein eigener JWT-Parser oder
Authorization Server implementiert. Grundlage:
[Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

Beide Anwendungen erhalten dieselbe Konfiguration, beispielsweise:

```dotenv
OAUTH2_ISSUER_URI=https://identity.example.org/realms/ddb
OAUTH2_AUDIENCE=ddb-api-v3
OAUTH2_JWK_SET_URI=
DDB_DEV_WRITE_TOKEN=
```

Die URL ist ein Beispiel, kein mitgelieferter Dienst. Issuer und Audience sind
gemeinsam erforderlich. Ohne explizite JWKS-URL verwendet Spring die Discovery
des Issuers. Schlüsselrotation und das Zwischenspeichern öffentlicher
Prüfschlüssel übernimmt das Framework; RDF-Antworten werden nicht gecacht.
Der Anbieter muss zur erstmaligen Schlüsselermittlung und bei notwendigen
Aktualisierungen erreichbar sein. Ein Ausfall öffnet keine Schreibrechte.

### Keycloak als mögliche spätere Anbindung

1. Einen Realm und einen vertraulichen Import-Client anlegen; **Client
   authentication** und **Service accounts roles** aktivieren. Browser- und
   Passwort-Flows abschalten, wenn ausschließlich Client Credentials gebraucht
   werden.
2. Einen Audience-Mapper für den API-Wert `ddb-api-v3` mit **Add to access token**
   hinzufügen. Dieser Wert muss `OAUTH2_AUDIENCE` entsprechen.
3. Client Scopes `records:write` und/oder `items:write` mit **Include in token
   scope** anlegen und nur für den jeweiligen Import freigegebenen Clients
   zuweisen. Der benötigte Scope muss im Claim
   `scope` des Access-Tokens stehen; eine gleichnamige Realm-Rolle genügt nicht.
4. Kurze Access-Token-Laufzeit, etwa fünf Minuten, setzen. Das Client-Secret im
   Secret Store verwalten; niemals an Swagger oder öffentliche Browser verteilen.
5. Issuer aus den Realm-Metadaten übernehmen. Tokens per Client Credentials am
   Keycloak-Token-Endpunkt anfordern und als Bearer zur API senden.

Die API stellt keinen eigenen `/oauth2/token`-Endpunkt bereit. Der Helm-Chart
installiert Keycloak ebenfalls nicht. Details:
[Keycloak-Administration](https://www.keycloak.org/docs/latest/server_admin/index.html)
und [OIDC-Endpunkte](https://www.keycloak.org/securing-apps/oidc-layers).

## Grenzen und Veröffentlichung

`records:write` und `items:write` sind Import-/Administrationsrechte für den
jeweiligen Ressourcentyp: Es gibt keine Trennung seiner DDB-IDs nach Mandant oder
Eigentümer. Diese Rechte nur vertrauenswürdigen Importdiensten geben. `If-Match` verhindert versehentliche
konkurrierende Änderungen, ersetzt aber keine Berechtigungsprüfung.

HTTP ist ausschließlich für lokale Entwicklung über Loopback gedacht. Im
öffentlichen Betrieb HTTPS am Ingress erzwingen, den API-Service intern halten
und globale Anfrage-, Verbindungs- und Größenlimits am Ingress setzen. Der
Gateway-Quotenfilter greift nach der Authentifizierung und begrenzt deshalb keine
Flut ungültiger Tokens. Die vorhandenen RDF-Größen-, Parallelitäts- und
Warteschlangengrenzen bleiben bestehen.

Ein gestohlenes Bearer-Token ist bis zum Ablauf nutzbar; kurze Laufzeiten,
geschützte Client-Secrets und Schlüsselrotation sind nötig. Das lokale
Entwicklungstoken kann nur durch Rotation gesperrt werden. Schutz vor jedem
denkbaren Missbrauch lässt sich nicht garantieren. Access-Logs enthalten keine
Tokens; Header-Client-IDs bleiben unbestätigte Angaben, siehe
[Logging und Auswertung](logging.md).
