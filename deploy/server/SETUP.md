# Einmalige VPS-Einrichtung

Diese Anleitung verändert keinen laufenden Dienst, bis der Prüfpunkt ausdrücklich bestanden ist. Die produktive `.env` bleibt ausschließlich auf dem VPS unter `/opt/noizycologne/deploy/server/.env` und wird nicht aus GitHub Actions übertragen.

## 1. Bestehenden Zustand nur lesen

```bash
ssh deploy@tsa.noizycologne.de
cd /opt/noizycologne
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Status}}'
docker volume ls
docker compose ps 2>/dev/null || true
find /opt/noizycologne -maxdepth 3 -type f -printf '%p\n' | sort
```

Vorhandene Caddy-Container, deren Compose-Datei und die beiden Caddy-Volume-Namen müssen dokumentiert werden. Die tatsächlichen Namen werden nicht angenommen. Insbesondere niemals `docker compose down -v` und niemals `docker volume rm` verwenden.

## 2. Deployment-Dateien bereitstellen

Die Pipeline kopiert beim Deployment `Caddyfile`, `compose.yaml`, `deploy.sh` und `backup.sh` nach `/opt/noizycologne/deploy/server/`. Für eine manuelle Erstinstallation müssen diese Dateien dort aus dem freigegebenen Commit liegen. Die bestehende Landingpage-Konfiguration darf nicht durch eine alternative Domain-Konfiguration ersetzt werden. Die bisherige `/opt/noizycologne/compose.yaml` und ihr `Caddyfile` bleiben als Rückweg erhalten. Der neue Deploy-Script verwendet `.env` und `compose.yaml` aus diesem Verzeichnis explizit.

```bash
cd /opt/noizycologne/deploy/server
install -m 0600 /dev/null .env
${EDITOR:-vi} .env
```

Die Datei wird anhand `.env.example` ausgefüllt. Zusätzlich werden die exakten Volume-Namen aus Schritt 1 eingetragen. Falls PostgreSQL bisher noch kein Volume besitzt, wird nur für `POSTGRES_DATA_VOLUME` ein neuer eindeutiger Name festgelegt und danach einmalig erstellt:

```bash
docker volume create <postgres-volume-name>
```

Für Caddy werden ausschließlich die bereits inventarisierten Volumes verwendet. Fehlt eines davon, abbrechen und nicht mit einem geratenen Namen fortfahren.

Für die privaten Standardflyer wird zusätzlich ein eigenes externes Volume benötigt. Es enthält keine öffentlich gelisteten Dateien und darf nicht mit dem Datenbank- oder Caddy-Volume geteilt werden:

```bash
docker volume create <media-volume-name>
```

Der exakt verwendete Name kann als `MEDIA_DATA_VOLUME` in der VPS-`.env` eingetragen werden. Fehlt die Variable beim ersten Rollout, verwendet `deploy.sh` ausschließlich für dieses neue Flyer-Volume den festen Namen `noizycologne_media_data` und legt es nur an, wenn es noch nicht existiert. PostgreSQL- und Caddy-Volumes bleiben weiterhin zwingend konfiguriert und werden niemals automatisch ersetzt.

## 3. Team-Session konfigurieren und Konfiguration prüfen

```bash
openssl rand -hex 32
```

Der zufällig erzeugte Wert kommt als `TEAM_SESSION_SECRET` in die VPS-`.env`. `TEAM_USER` und `TEAM_PASSWORD_HASH` bleiben die bestehenden Team-Zugangsdaten; der bcrypt-Hash wird nicht geändert. Weder das Klartextpasswort noch der Session-Schlüssel werden in Repository, Shell-History oder GitHub-Logs geschrieben. Danach nur prüfen:

Die Flyer-Genreanalyse verwendet ausschließlich lokale Tesseract-OCR im Backend-Container. Es werden keine Flyer an einen externen KI-Dienst übertragen und kein API-Schlüssel benötigt. Für das Container-Image muss Tesseract inklusive der Sprachdaten `deu` und `eng` installiert sein; die Analyse bleibt bei fehlender Installation mit einer verständlichen Fehlermeldung deaktiviert.

```bash
cd /opt/noizycologne/deploy/server
docker compose config >/dev/null
docker compose run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

Die neue Team-Domain verwendet keine Browser-Basic-Auth mehr. Die Login-Seite prüft die vorhandenen Zugangsdaten serverseitig und setzt eine signierte, `HttpOnly`-, `Secure`- und `SameSite=Lax`-Session. Caddy leitet `/api/v1/*` nur nach erfolgreicher Session-Prüfung an das interne Backend weiter.

## 4. Erster Rollout

Vorher müssen DNS für `tsa.noizycologne.de` und `noizycologne.de` auf den richtigen VPS zeigen, und nur TCP/UDP 80/443 dürfen von außen erreichbar sein. Die Pipeline übernimmt anschließend den Rollout:

```bash
cd /opt/noizycologne
DOCKER_CONFIG=<temporäres-docker-config-verzeichnis> ./deploy/server/deploy.sh <freigegebener-commit-sha>
```

Der normale GitHub-Actions-Weg ist vorzuziehen. Der Compose-Projektname bleibt `noizycologne`; der bestehende Caddy-Service wird aktualisiert. `deploy.sh` validiert zuerst Compose und Caddy, zieht die Images, wartet auf PostgreSQL/Backend/Frontend und aktualisiert erst danach Caddy. Bei Fehlern wird der vorherige Image-Tag wieder gestartet; beim ersten Cutover wird, sofern die alte Compose-Datei vorhanden ist, der bisherige Caddy-/Landing-/Smoke-Stack wieder gestartet. Es gibt keinen `down -v`-Schritt. Der alte Smoke-Service wird erst nach erfolgreichem authentifiziertem Live-Smoke-Test entfernt.

## 5. Backups und Abnahme

Backupwerkzeug bleibt `backup.py` / `backup.sh`; der fail-closed Wrapper
`backup-wrapper.sh` stellt die von `backup.py` zwingend verlangte Ruhe her: Er
stoppt nur die vorher laufenden Dienste (`api`, `frontend`) und startet sie über
einen `EXIT`-`trap` auch bei Fehlern wieder. `--offsite-dir` ist Pflicht und muss
auf ein bereits eingerichtetes Verzeichnis mit Modus `0700` zeigen; ohne
verifizierte Offsite-Kopie entsteht keine Generation. `prune` trennt die
Aufbewahrung (14 Tage verifiziert, 3 Tage lokale-only). Kein `source .env`: Die
nichtgeheimen Einstellungen stehen in `/etc/noizycologne/backup.conf`
(`NOIZY_SERVER_DIR`, `NOIZY_BACKUP_OUTPUT`, `NOIZY_OFFSITE_DIR`,
`NOIZY_BACKUP_RECIPIENT`, optional `NOIZY_COMPOSE_PROJECT` und
`NOIZY_BACKUP_ALARM_HOOK`), die persistente `.env` bleibt bei 0600 und wird nur
von Docker Compose gelesen. Die systemd-Vorlagen `noizy-backup.service/.timer`
und `noizy-backup-alert.service/.timer` werden erst nach einem beobachteten
manuellen Lauf installiert und aktiviert; der Wartungsfenster-Vorschlag ist
täglich 03:20 Uhr. Details, read-only Checkliste, Alarm-Hook-Schema und der
monatliche Restore-Test auf separatem Host: [BACKUP-RESTORE.md](../../docs/BACKUP-RESTORE.md).
**Produktiv NICHT VERIFIZIERT:** Offsiteziel, Schlüssel, Timer, Alarmierung und
Wiederanlauf wurden in diesem Paket nicht live geprüft.

Vor der Freigabe prüfen:

```bash
docker compose ps
curl --fail --silent --show-error https://noizycologne.de/
curl --head --fail --silent https://tsa.noizycologne.de/
curl --fail --silent https://api.noizycologne.de/ | grep -q 'Not found'
```

Der Team-Host muss ohne Basic-Auth mit `401` antworten; `/api/v1/*` ist nur über denselben authentifizierten Team-Host erreichbar. Backend und PostgreSQL dürfen in `docker compose ps` keine veröffentlichten Host-Ports besitzen.

## JavaScript-Crawler (Overlay im Deployment, Aktivierung ausdrücklich)

Der Browser-Fallback ist Bestandteil des qualifizierten Deploymentwegs, nicht
mehr nur eine spätere Option: `.github/workflows/ghcr.yml` baut und veröffentlicht
Worker und Egress-Gateway als Images mit derselben geprüften Commit-SHA wie
Backend und Frontend (keine `latest`-Freigabe) und überträgt
`compose.browser.yaml`, `seccomp_profile.json` und `deploy.sh` unter
`/opt/noizycologne/deploy/server/releases/<SHA>/`. `deploy.sh` ergänzt den
Compose-Aufruf bei ausdrücklich in der persistenten `.env` gesetztem
`DISCOVERY_BROWSER_ENABLED=true` um das Overlay, startet beide Dienste mit
demselben Release und prüft nach dem Rollout per
`deploy.sh --browser-smoke` den internen Gateway-/Worker-Pfad; bei Fehlern wird
auf das vorherige Manifest und die vorherige Browserwahl zurückgerollt.

Die Auslieferung ist nicht gleichbedeutend mit Aktivierung: Ohne ausdrücklich
gesetztes `DISCOVERY_BROWSER_ENABLED=true` in der persistenten `.env` bleiben die
Browserdienste aus. Vor dem Start erzwingt `deploy.sh` ein separates
`BROWSER_WORKER_TOKEN` (mindestens 32 Zeichen, bei Bedarf einmalig erzeugt, Wert
wird nicht ausgegeben), ein absolutes, inhaltlich mit dem qualifizierten Release
identisches `BROWSER_SECCOMP_PROFILE`, mindestens 1 GiB verfügbaren RAM und eine
Wegwerf-Sandboxprüfung mit echtem Profil und Containerlimits. Kein
`--no-sandbox`, kein privilegierter Container, kein Docker-Socket und keine
veröffentlichten Workerports: Der Renderer erreicht nur das isolierte interne
Browsernetz, das Gateway hat kontrolliert Egress. Schutzmechanismen bei
Kernel-/AppArmor-Problemen nicht abschalten.

**Produktiv NICHT VERIFIZIERT:** Diese Anleitung beschreibt nur den im Repo
belegten Ablauf; hier wurden keine Live-Dienste geprüft, keine Browsercontainer
gestartet und keine Produktions-Smoke-Ergebnisse vorweggenommen. Konfiguration,
Netztopologie, Ressourcenbudgets, lokale Sandbox-Fixtures und die Grenzen der
bestehenden Inventur: [Browser-Crawler](../../docs/BROWSER-CRAWLER.md).

## Werkseinstellungen (vollständiger Datenreset)

Unter **Einstellungen → Werkseinstellungen** gibt es eine gesondert bestätigte Funktion
zum vollständigen Leeren der **Anwendungsdaten**, einschließlich sämtlicher hochgeladener
Flyer und Bilder im vorhandenen `media_data`-Mount. **Der Docker-Volume-Container
selbst wird nicht entfernt.** Der Login bleibt erhalten, weil `TEAM_USER`,
`TEAM_PASSWORD_HASH` und `TEAM_SESSION_SECRET` nur im persistenten VPS-`.env`
liegen. Auch Flyway-Migrationen, SSL, DNS, GHCR, Caddy und andere Volumes bleiben erhalten.

Der Reset ist nur bei aktivem Login möglich, erfordert eine frische Vorschau,
das **erneute Login-Passwort**, eine ausdrückliche Einwilligung, die exakte Eingabe
`Kompletter Reset` und eine weitere Sicherheitsabfrage. Die Oberfläche ruft ausschließlich
eine serverseitige Next.js-Route auf. Diese spricht die für den öffentlichen Caddy
**nicht erreichbare** interne Backend-API über das Docker-Netz an und authentisiert
sich dort mit `FACTORY_RESET_SECRET` (mindestens 32 Zeichen).

`deploy.sh` erzeugt `FACTORY_RESET_SECRET` **einmalig kryptografisch zufällig**
im bestehenden persistenten `.env`, falls er fehlt. Der Wert wird nicht ausgegeben,
bei späteren Deployments nicht rotiert und nur an API und Frontend weitergereicht.
Wenn bereits ein Schlüssel konfiguriert ist, wird er beibehalten. Der Reset ist
bei fehlendem/ungültigem Schlüssel gesperrt, ohne den laufenden Dienst zu beeinträchtigen.

**Fail-closed:** Nicht erwartete PostgreSQL-Tabellen, ein fehlendes/unsicheres
Medienverzeichnis oder ein laufender Discovery-Prozess blockieren das Löschen.
Die Datenbanktabellen werden in **einer Transaktion** geleert; `flyway_schema_history`
bleibt erhalten. Die Dateibereinigung beginnt erst nach erfolgreichem Commit.
Falls danach ein Dateisystemfehler auftritt, meldet die UI ausdrücklich einen
**Teil-Erfolg**; ein Datenbank-Rollback ist zu diesem Zeitpunkt nicht mehr möglich.
Vor der ersten produktiven Rücksetzung sollte ein geprüftes externes Backup
von PostgreSQL **und** `media_data` existieren.

Nach dem Reset die Anwendung neu laden; offene Browserzustände anderer Teammitglieder
sind möglicherweise veraltet. Es wird **niemals** ein Reset bei Deployment,
Start oder Datenbankmigration automatisch ausgeführt.
