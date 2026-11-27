# Lokaler Docker-Teststack

Baut Backend und Frontend aus dem aktuellen Branch und startet PostgreSQL, API,
PWA und einen lokalen Caddy ausschließlich auf Loopback:

```sh
docker compose -f deploy/local/compose.yaml --env-file deploy/local/.env up -d --build
docker compose -f deploy/local/compose.yaml --env-file deploy/local/.env ps
curl -k https://localhost/login
curl http://127.0.0.1:8080/actuator/health/readiness
```

- **PWA (HTTPS): https://localhost/login** — `NOIZY_PUBLIC_ORIGIN=https://localhost`.
  Caddy terminated HTTPS mit `tls internal` (selbstsigniertes Zertifikat, keine
  ACME, kein Produktionshostname). Der Browser zeigt beim ersten Aufruf eine
  Zertifikatswarnung; die Warnung muss bewusst akzeptiert werden (`curl -k`).
- API direkt (nur für Healthchecks): http://127.0.0.1:8080 .
  `http://127.0.0.1:3000` bleibt nur für den internen Healthcheck erreichbar;
  ein Browser-Login darüber wird wegen der HTTPS-CSRF-Prüfung mit 403 abgewiesen.
- **Lokales Konto: `local` / `local-test-only`** (bcrypt-Hash nur in `.env`).
- Caddy spiegelt die Produktions-Routing-Grenze: `/api/v1/*` wird über
  `/api/auth/verify` authentifiziert und mit `X-Noizy-Api-Key`,
  `X-Noizy-Proxy-Host: localhost`, `X-Noizy-Proxy-Proto: https` an die API
  weitergegeben; alles andere geht an die PWA mit `X-Noizy-Auth-Key` und
  `X-Noizy-Client-IP`. Nur lokale Secrets aus `.env`, keine Secrets im Caddyfile.
- `.env` ist gitignored und enthält Zufallssecrets; `.env.example` enthält nur
  Platzhalter. Das `$` im bcrypt-Hash ist dort als `$$` geschrieben, damit
  Compose es nicht als Variablenreferenz interpretiert.
- `NOIZY_PUBLISH_ENABLED`, `INSTAGRAM_WORKER_ENABLED` und
  `DISCOVERY_EXTERNAL_CALENDAR_ENABLED` sind hier nur für lokale Tests aktiv.
  `INSTAGRAM_GATEWAY_ENABLED` bleibt bewusst `false`: mit `true` startet die
  Branch derzeit nicht, weil `graphApiInstagramGateway` ein
  `RestClient.Builder`-Bean verlangt, das Spring Boot 4 nicht automatisch
  bereitstellt. Das fail-closed Gateway ist auch so aktiv; es gibt keinen
  Meta-Token, keine öffentliche Bild-HTTPS-Basis und keine Review-Freigaben,
  es werden keine Beiträge gepostet.
- Keine `discovery_sources`-Zeile mit einer erfundenen MTC-URL anlegen. Die
  Quelle muss als echte öffentliche HTTPS-ICS-/JSON-URL bereitgestellt werden;
  `SafeUrls` blockt Loopback/private Hosts und Nicht-443-Ports absichtlich.

Stoppen: `docker compose -f deploy/local/compose.yaml --env-file deploy/local/.env down`
(Daten der PostgreSQL-, Medien- und Caddy-Volumes bleiben erhalten).
