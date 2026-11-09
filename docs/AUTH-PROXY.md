# AUTH, Teil 2: vertrauenswürdiger Proxy und persistente Sitzungen

## Befund, Entscheidung und Grenzen

SEC-03/SEC-04: Prozesslokale Maps und frei angegebene IP-/Benutzernamen erlaubten
unbegrenzte Buckets und verloren Widerrufe beim Neustart. Next verwendet jetzt den
internen PostgreSQL-Store aus V24. Es wird keine weitere Datenbank eingeführt.

Caddy überschreibt `X-Noizy-Auth-Key` und `X-Noizy-Client-IP` sowohl für die
Frontendweiterleitung als auch für `forward_auth`. Die Adresse stammt aus
`{remote_host}`, also der direkten TCP-Verbindung. Clientseitige `X-Real-IP` und
`X-Forwarded-For` bestimmen keine Buckets. Ein vorgeschalteter weiterer Proxy
benötigt eine eigene ausdrücklich geprüfte Vertrauenskette; derzeit teilen seine
Clients gegebenenfalls denselben konservativen IP-Bucket.

Der gemeinsame `AUTH_SERVICE_SECRET` liegt nur in Caddy, Next und API. Er ist vom
API-Proxy- und Reset-Schlüssel getrennt. Die API veröffentlicht die Auth-Endpunkte
nicht über Caddy. Next akzeptiert seine Anmeldung/Sitzungsprüfung nur hinter dem
vertrauenswürdigen Proxy. Direkte Entwicklungszugriffe benötigen ebenfalls einen
explizit konfigurierten vertrauenswürdigen Proxy; es gibt keinen Dev-Bypass.

## Sitzungen und Anmeldeversuche

Sitzungen haben weiterhin acht Stunden Laufzeit. Neue Nonces werden vor der
Cookieausgabe registriert; versioniert abgeleiteter HMAC-Schlüssel, Subjekt, Zeitwerte, Nonceformat, TTL und eine
SHA-256-Generation aus Konto, Passwort-Hash und Session-Schlüssel werden geprüft.
Ein Passwort-/Schlüsselwechsel invalidiert alte Sitzungen. Widerrufe werden vor
Bestätigung des Logout persistiert. Storeausfälle verweigern Anmeldung und
Sitzungsfreigabe; ein nicht bestätigter Logout liefert 503 und löscht das Cookie
nicht als vermeintlichen Erfolgsnachweis. Cookies bleiben HttpOnly/Secure/Lax.
Auth-Antworten sind `no-store`.

Der Login begrenzt JSON auf 4 KiB, Konto auf 100 und Passwort auf 1024 Zeichen.
Auch ein unbekanntes Konto durchläuft den Passwortvergleich. Bucket-Kennungen
verwenden immer das konfigurierte Konto und die vertrauenswürdige Adresse,
ausschließlich als SHA-256 mit getrennten Namensräumen. Fehlende/ungültige
vertrauenswürdige Adresse fällt in einen gemeinsamen begrenzten Bucket.
Sitzungszustand und Limits gelten über Frontend-/API-Neustarts hinweg; keine
unbegrenzten Frontend-Maps und keine Cookies/IPs im persistenten Store.

Zustandsänderungen sind im Store serialisiert. Sitzungsprüfungen lesen den
zuletzt committeten Zustand ohne diesen globalen Schreiblock, damit parallel
startende UI-Requests keine falsche Abmeldung auslösen. Bereits begonnene,
autorisierte Requests werden durch Logout nicht rückwirkend abgebrochen.
Abgelaufene angefragte Sitzungen werden entfernt; alle Schreibzugriffe bereinigen
abgelaufenen Zustand. Physisch verbleibende unbenutzte Zeilen sind durch die
festen Kapazitäten begrenzt. Der Reset erhält die Login-Infrastruktur.

Originprüfung verwendet ausschließlich `NOIZY_PUBLIC_ORIGIN`, in Produktion
`https://tsa.noizycologne.de`. Schreibzugriffe benötigen diesen Origin und dürfen
keine widersprechenden Fetch-Metadata tragen; Forwarded-Host/-Proto setzen den
Vertrauensanker nicht. Sichere GET/HEAD dürfen ohne Origin lesen. Der Reset-
Reauthentisierungsweg wartet ebenfalls auf den persistenten Limiter; ein
Storefehler bestätigt keinen Reset. Die Revision-/Mutationssperre folgt separat.

## Einführung und Rollback

`deploy.sh` erzeugt `AUTH_SERVICE_SECRET` einmalig mit 256 Bit Zufall in der
bestehenden privaten `.env` (0600). Bestehende Werte bleiben erhalten; leere,
zu kurze oder für die Konfiguration ungeeignete Werte blockieren vor dem
Containerwechsel. Erlaubt: 32–128 Buchstaben/Ziffern/Unterstrich/Bindestrich.
API, Caddy und Frontend müssen gemeinsam auf den neuen Stand wechseln.

Bestehende Cookies besitzen keine registrierte Nonce/Generation und verlangen
nach Aktivierung einmalig erneute Anmeldung. Konto, Passwort-Hash, Session-
Schlüssel, Daten und Medien werden nicht geändert. Ein fehlgeschlagener Deploy
nutzt den bestehenden gemeinsamen Manifest-/Image-Rollback; V24 bleibt bestehen.

Neue Cookies verwenden einen mit `noizy-session-v2` abgeleiteten Signierschlüssel.
Eine Legacyversion mit direkter Signatur unter `TEAM_SESSION_SECRET` kann diese
Cookies auch nach Rollback nicht akzeptieren; ein neuer persistenter Widerruf
wird dadurch nicht durch Rücknahme auf einen alten Parser umgangen.

Sicherheitsgrenze des Rollback: Die frühere zustandslose Version besitzt weiterhin
ihre ursprünglichen Schwächen. Kopierte, vor dem Hardening ausgestellte Legacy-
Cookies können nach Rücknahme wieder bis zu ihrem damaligen Ablauf gelten.
Ein solcher Rollback ist deshalb keine Abnahme des gehärteten Sessionvertrags.
Bei diesem Notfall sind ein bewusst freigegebener Session-Schlüsselwechsel
(alle Benutzer melden sich erneut an) oder ein vorübergehend gesperrter Zugang
nötig. Keine automatische Rotation bestehender Zugangsdaten und keine
Down-Migration. Nach Rücknahme Exposition/Erreichbarkeit prüfen und die Korrektur
vorwärts ausliefern.

## Reproduzierbare Prüfungen

- Auth-/Route-Regressionen: Tokenformat, Generation/Rotation/TTL, Passwortvergleich,
  Proxy/Origin/Spoofing, fünf Versuche trotz wechselnder Benutzernamen, Größen-
  und Typgrenze, bestätigter Cookie-/Logoutausgang, Ausfälle, Replay und no-store.
- Echte PostgreSQL-Integration in isoliertem Testcontainer: Widerruf, Kapazitäten,
  TTL, parallele Zugriffe, gültige Sessionprüfung bei gehaltenem Schreiblock.
- Echter Caddy im isolierten Docker-Netz: überschreibt gefälschte API-/Auth-/IP-
  Header, verlangt Session und veröffentlicht weder Reset noch Authstore.
- Deploy-Fixture: einmalige private Schlüsselanlage, unveränderte Zugangsdaten,
  wiederholter Deploy, invalide bestehende Konfiguration und bestehender Rollback.
- `frontend/tests/visual-auth.mjs`: echtes Next und Chromium auf HTTPS-Loopback,
  kurzlebiges lokales Store-Fixture, sechs Szenarien (390/1440 px × drei Themes).
  Anmeldung, nicht bestätigter Logout, Widerruf, kopiertes Cookie nach echtem
  Frontendprozess-Neustart und verständlicher Storeausfall. Dieser Test ersetzt
  keinen echten PostgreSQL-/VPS-Neustart; Datenbank und Caddy werden separat oben
  geprüft. Temporärer Schlüssel, Zertifikat, Prozesse und Store werden aufgeräumt.

```sh
PLAYWRIGHT_MODULE=/tmp/noizy-discovery-visual/node_modules/playwright/index.mjs \
  node frontend/tests/visual-auth.mjs
```

Bestehende UI-Fixtures erhalten registrierte, ausschließlich lokale Sitzungen:

```sh
PLAYWRIGHT_MODULE=/tmp/noizy-discovery-visual/node_modules/playwright/index.mjs \
  AUTH_VISUAL_SCRIPT=visual-preferences.mjs \
  node frontend/tests/visual-auth.mjs
```

Der Wrapper setzt ausschließlich wegwerfbare Test-Credentials und prüft lokale
Ziele. Nexts lokale dotenv-Expansion von Dollarzeichen wird für den generierten
Test-Passwort-Hash berücksichtigt; vorhandene `.env.local` wird nicht geändert.
Das Darstellungsskript verwendet die aktuelle Discovery-Filter-API und wählt die
zu prüfende Kartenansicht ausdrücklich. Weitere bestehende Visualskripte sind
als feste Auswahl zulässig; keine beliebige Shellausführung.

Pflicht-QUICK läuft im Commit-Hook, FULL in GitHub Actions für den aktuellen
PR-/main-Stand. Produktions-Login-/Logout-/Replay- und Browser-Smoke laufen erst
nach exaktem main-FULL beim ausdrücklich freigegebenen Deploy. Ein noch laufender
oder fehlgeschlagener Check ist keine Abnahme; produktive Beobachtungen werden
separat im PR nachgetragen. Live-IDE/Safari/VoiceOver sind hier nicht nachgewiesen.
