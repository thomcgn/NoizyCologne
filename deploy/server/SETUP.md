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

Einen Cronjob für `backup.sh` einrichten und das erzeugte Verzeichnis außerhalb des Docker-Volumes regelmäßig auf ein unabhängiges Ziel kopieren. Vor der Freigabe prüfen:

```bash
docker compose ps
curl --fail --silent --show-error https://noizycologne.de/
curl --head --fail --silent https://tsa.noizycologne.de/
curl --fail --silent https://api.noizycologne.de/ | grep -q 'Not found'
```

Der Team-Host muss ohne Basic-Auth mit `401` antworten; `/api/v1/*` ist nur über denselben authentifizierten Team-Host erreichbar. Backend und PostgreSQL dürfen in `docker compose ps` keine veröffentlichten Host-Ports besitzen.
