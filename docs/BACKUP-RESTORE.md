# Backup/Restore: BKP-01 / BKP-02

> **Status dieses Pakets (B1): PRODUKTIV NICHT VERIFIZIERT.** Diese Änderung
> wurde ohne Live-Zugriff, ohne Produktivmutation und ohne Deployment erstellt.
> Alle Aussagen über den realen VPS, das Offsiteziel, Schlüssel, Timer und
> Alarmierung bleiben ausdrücklich ungeprüft.

## Entscheidung und Sicherheitsgrenze

Ausgangspunkt `main` 160cd81: `backup.sh` erzeugte nur SQL, benötigte exportierte
Shellvariablen und sicherte keine Medien/Wiederanlaufkonfiguration. Im SHA-Release-
Layout liegt die persistente `.env` außerhalb des Skriptverzeichnisses. Ein SQL-Dump
allein ist kein wiederherstellbarer Anwendungsstand.

`deploy/server/backup.py` / `backup.sh` bleiben das Werkzeug. Neu ist der
fail-closed Wrapper `deploy/server/backup-wrapper.sh` mit den systemd-Vorlagen
`noizy-backup.service` / `noizy-backup.timer` sowie dem read-only Alarm-Check
`backup-alert.py` mit `noizy-backup-alert.service` / `.timer`. Die Vorlagen werden
**nicht** installiert oder aktiviert; sie sind Muster für eine ausdrücklich
freigegebene Einrichtung durch den Betreiber. Docker Compose parst die
Konfiguration; niemals `source .env`. Zugangsdaten bleiben im DB-Container und
gelangen nicht in Host-Prozessargumente oder Logs. Die GPG-Empfängeridentität
wird ausschließlich als expliziter Schlüsselfingerprint akzeptiert, ohne
automatischen Schlüsselabruf.

**Konsistenzvoraussetzung:** `backup.py:127–129` verlangt zwingend, dass API und
Frontend gestoppt und nicht pausiert/restartend sind. Der Wrapper stellt diese
Ruhe über `trap` her: Er merkt sich vorher **nur die tatsächlich laufenden**
Dienste, stoppt genau diese, führt Backup und anschließend `prune` aus und startet
dieselben Dienste in jedem Exit-Pfad wieder – auch bei Stop-, Backup-, Prune- oder
Hook-Fehlern. Waren Dienste schon gestoppt, werden sie nicht heimlich gestartet.
Der Neustart ist kein Erfolgsnachweis; Health/Login-Smoke bleibt eine eigene,
vom Betreiber freigegebene Prüfung. Pausierte/laufende Dienste und andere laufende
Container mit schreibendem Medienmount führen zum Abbruch. Während der Wartung
dürfen auch Administratoren, Cronjobs oder externe Prozesse keine
DB-/Medienänderungen vornehmen; Docker-Diagnostik kann Host-Dateischreiber nicht
zuverlässig ausschließen. Deployment/Reset/Uploads bleiben währenddessen gesperrt.
Für ein wartungsfreies Backup wäre ein kooperativer Mutations-/Snapshotmechanismus
nötig; eine solche Garantie wird hier nicht behauptet.

Invarianten: aufgezeichnete unveränderliche App-Release-SHA, vollständiger Custom-Format-Dump, erfolgreiche Flyway-Historie,
alle Tabellenzählungen, alle Medien einschließlich Orphans, passende Prüfsummen
aller `media_assets`-Referenzen, Konfiguration mit unverändertem persistentem
Login-/Session-/Reset-Geheimnis. Keine automatische Datenmigration oder Löschung.
Fehlende Medien, unbekannte/leere Datenbank, unsichere Archive und Änderungen
während der Sicherung sind Fehler. Ein bewusst leeres Medienvolume verlangt
`--allow-empty-media` bei einem **manuellen** Aufruf; der Wrapper setzt dieses Flag
nicht. Keine partiellen Generationen bei Dump-/Archiv-/GPG-Fehlern.

## Fail-closed Backup-Ablauf und Wartungsfenster

Der Wrapper liest ausschließlich nichtgeheime Variablen aus seiner systemd-
`EnvironmentFile` bzw. der Shell-Umgebung:

| Variable | Bedeutung |
| --- | --- |
| `NOIZY_SERVER_DIR` | persistentes Serververzeichnis mit privater `.env` (0600) |
| `NOIZY_BACKUP_OUTPUT` | lokales Ausgabeverzeichnis (0700), außerhalb der Docker-Volumes |
| `NOIZY_OFFSITE_DIR` | **bereits eingerichtetes**, zugriffsgeschütztes Offsiteziel (0700) |
| `NOIZY_BACKUP_RECIPIENT` | expliziter GPG-Fingerprint (40–64 Hex-Zeichen) |
| `NOIZY_COMPOSE_PROJECT` | optional, Standard `noizycologne` |
| `NOIZY_BACKUP_ALARM_HOOK` | optionaler, ausführbarer Alarm-Hook (Schnittstelle siehe unten) |

Fehlt eine Pflichtvariable, ist `.env`/Offsiteziel nicht privat oder vorhanden
oder ist der Fingerprint ungültig, endet der Lauf vor jedem Docker-Zugriff.
`--offsite-dir` ist bei `backup.py backup` **zwingend**; ein lokales Backup ohne
verifizierte Offsite-Kopie wird nicht erzeugt. Ein nicht vorhandenes Offsite-
Verzeichnis wird nicht angelegt (Tippfehler dürfen kein scheinbares Offsite
erzeugen). Die Offsite-Kopie wird vor der Veröffentlichung der lokalen Generation
bytegleich verifiziert; bei Kopierfehlern bleibt die zuletzt gültige Generation
erhalten und es entsteht keine neue `.tar.gpg`/`.tar.json`. Das verschlüsselte
Manifest trägt `offsite: VERIFIED_COPY`; dieselbe Information steht in der
unverschlüsselten Statusdatei `.tar.json` zusammen mit `created_at`, `sha256`,
`bytes` und `media_count`. Nur dieses Format darf als Generation betrachtet werden.

**Wartungsfenster:** Der Timer ist ein Vorschlag für täglich 03:20 Uhr
(`RandomizedDelaySec=10m`). API und Frontend sind während des Backups kurz
gestoppt; das ist eine echte, wenn auch kurze Downtime. Der Betreiber legt das
Fenster fest, informiert das Team und aktiviert den Timer erst nach einem
beobachteten manuellen Lauf. Kein automatisches Deployment, kein Factory Reset,
kein `down -v`, keine Volume-Löschung.

**Aufbewahrung – zwei getrennte Regeln:** `backup.py prune --output <verzeichnis>`
läuft nach jeder erfolgreichen Sicherung automatisch aus dem Wrapper und ist auch
manuell aufrufbar (`--local-retention-days N`, Standard 3).

- Verifizierte Generationen (`offsite: VERIFIED_COPY`): älter als 14 Tage werden
  entfernt; die zwei neuesten bleiben immer.
- Lokale-only/Status unbekannter Generationen: älter als 3 Tage werden entfernt;
  die neueste bleibt immer. Ohne Offsite wächst der Bestand dadurch nicht
  unbegrenzt. Nicht verifizierbare oder beschädigte Dateien werden nie gelöscht.

Die Retention ersetzt weder Restoreprüfungen noch externen Schutz vor Löschung;
die Aufbewahrung auf dem Offsiteziel bleibt eine eigene Betreiberverantwortung.

## Alarm-Hook-Schnittstelle (kein schwerer Monitoring-Stack)

Der Hook ist eine vom Betreiber bereitgestellte ausführbare Datei; er erhält
**eine JSON-Zeile auf stdin**, keine Argumente mit Geheimnissen. Ein von Null
verschiedener Hook-Exit wird nur als Zustellfehler gemeldet, nie als Backup-Erfolg.
Der Wrapper sendet `BACKUP_OK` (mit `sha256` und `generation`) oder
`BACKUP_FAILED` (`reason_code` aus `MISSING_CONFIG`, `STOP_FAILED`,
`BACKUP_FAILED`, `RESTART_FAILED`, `PRUNE_FAILED`, `INSPECTION_FAILED`).

`backup-alert.py` ist ein read-only Check ohne Docker-/Datenbankzugriff. Er sendet
dieselbe Feldstruktur, ergänzt um `events`, `backup_age_hours` und
`restore_age_days`, und meldet `BACKUP_STALE` (Standard 36 h),
`SHA256_MISMATCH`, `OFFSITE_NOT_VERIFIED`, `RESTORE_STALE` (Standard 35 Tage)
oder `NO_BACKUP`. Der externe Timer läuft unabhängig vom Backup-Timer, damit auch
ein ausgebliebener Backup-Lauf alarmiert. Schwellenwerte sind Parameter, keine
neue Infrastruktur; der Betreiber entscheidet über das Ziel (Mail, Ticket,
Webhook) außerhalb dieses Repositories.

```sh
python3 deploy/server/backup-alert.py \
  --output /geschuetzt/backups \
  --restore-state /geschuetzt/restore-state.json \
  --hook /usr/local/bin/noizy-alarm-hook
```

`restore-state.json` wird nach einem erfolgreichen monatlichen Restore-Test vom
Betreiber geschrieben und enthält nur `{ "tested_at": "<ISO-8601 UTC>",
"result": "VERIFIED_FIXTURE_TARGET" }`. Fehlt die Datei, gilt der Restore-Test
als `RESTORE_STALE`.

## Operatorablauf – read-only VPS-Checkliste

Alles Folgende ist **lesend** und wurde in diesem Paket nicht ausgeführt:

```sh
ssh deploy@tsa.noizycologne.de
cd /opt/noizycologne/deploy/server
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Status}}'
docker compose --env-file .env -f compose.yaml ps
stat -c '%a %n' .env
find /opt/noizycologne -maxdepth 3 -type f -printf '%p\n' | sort
df -h /geschuetzt/backups
stat -c '%a %n' /geschuetzt/offsite
```

Vor der Freigabe prüfen: keine veröffentlichten Host-Ports für DB/API,
Fingerprint aus einem vertrauenswürdigen Kanal, ausreichend freier Speicher,
vorhandenes und nur für den Backup-Betreiber lesbares Offsiteziel, keine
laufenden Fremdcontainer mit Schreibzugriff auf `media_data`. Danach erst die
Dateien aus dem freigegebenen Release bereitstellen und einen **manuellen** Lauf
beobachten:

```sh
NOIZY_SERVER_DIR=/opt/noizycologne/deploy/server \
NOIZY_BACKUP_OUTPUT=/geschuetzt/backups \
NOIZY_OFFSITE_DIR=/geschuetzt/offsite \
NOIZY_BACKUP_RECIPIENT=<PUBLIC-KEY-FINGERPRINT> \
  bash /opt/noizycologne/deploy/server/backup-wrapper.sh
```

Die Variablen stehen in `/etc/noizycologne/backup.conf` (nur nichtgeheime Werte,
0600, nicht die persistente `.env`). `backup.sh` bleibt für gezielte manuelle
Aufrufe erhalten; `--offsite-dir` ist auch dort Pflicht:

```sh
NOIZY_SERVER_DIR=/opt/noizycologne/deploy/server \
  bash /opt/noizycologne/deploy/server/releases/<SHA>/backup.sh \
  --output /geschuetzt/backups --offsite-dir /geschuetzt/offsite \
  --recipient <PUBLIC-KEY-FINGERPRINT>
```

Ein vorhandenes Ausgabe-/Offsiteverzeichnis muss Modus `0700` besitzen. Geheime
`.env`, Dump und Medien liegen nur während der Erzeugung im privaten temporären
Arbeitsverzeichnis unverschlüsselt vor; ausreichend freien lokalen Speicher und
einen verschlüsselten Datenträger verwenden. Temporäres wird bei Erfolg/Fehler
entfernt. Stromausfall-/Datenträger-Recovery bleibt Betrieb. Nur die fertige
`.tar.gpg` übertragen; Transport, Verfügbarkeit und Retention des externen Ziels
bleiben Betreiberverantwortung. Nach jedem Lauf Health/Login-Smoke prüfen; der
Wrapper protokolliert Dateigröße, SHA256, Medienanzahl und Offsitezustand, keine
Inhalte.

## Isolierter Restore-Nachweis – monatlich auf separatem Host

```sh
bash deploy/server/restore-test.sh /geschuetzt/noizycologne-<generation>.tar.gpg
```

Der monatliche Test gehört auf einen **separaten Host** mit eigenem, offline
gesichertem privatem Schlüssel, niemals auf den Produktivbetrieb. Das Werkzeug
entschlüsselt in ein privates temporäres Verzeichnis, prüft Manifest,
Payload-/Medienhashes und Wiederanlaufkonfiguration. Es erstellt ausschließlich
zufällige `noizy-restore-*` Ressourcen: neue DB, neue Volumes, eigenes Passwort,
kein Netzwerk und keine veröffentlichten Ports. Bestehende Ziele, geerbte DSNs
und angegebene Produktivvolumes können nicht als Restoreziel verwendet werden.
`pg_restore` läuft ohne `--clean` in die leere Instanz. Tabellenzählungen,
Flyway-Versionen/Checksummen/Erfolg und Medienreferenzen werden verglichen;
Medien werden in ein neues Volume entpackt und erneut gehasht. DB/Medien-Testziele
werden anschließend entfernt. Rückgabestatus `VERIFIED_FIXTURE_TARGET` bestätigt
nur diesen Restore, keine Inbetriebnahme oder einen echten Login mit Produktivdaten.

Die im Manifest enthaltene PostgreSQL-Image-ID muss lokal verfügbar sein; das
Werkzeug zieht keine unbekannten Images nach. Für einen anderen Wiederanlaufhost
muss der Betreiber das geprüfte Image separat verfügbar halten und die ID prüfen.
Eine vollständige Wiederanlaufprobe umfasst anschließend – separat freigegeben –
Appversion, Login, Bildanzeige und Secret-/Volumezuordnung im isolierten Netz.
Nie wiederhergestellte Testdaten direkt auf Produktivvolumes kopieren. Nach
erfolgreichem Test `restore-state.json` mit `tested_at` und
`result: VERIFIED_FIXTURE_TARGET` fortschreiben; ohne diesen Nachweis alarmiert
der read-only Check.

## Nachweise und Grenzen

```sh
python3 -m unittest discover -s scripts/tests -p test_quality_backup.py -v
python3 -m unittest discover -s scripts/tests -p test_backup_wrapper.py -v
python3 -m unittest discover -s scripts/tests -p test_backup_alert.py -v
python3 scripts/test-backup-integration.py
```

Die ersten drei Befehle nutzen nur Fixture-Dateien, gemockte Prozessgrenzen und
temporäre Verzeichnisse; sie berühren keine produktive DB, keine Medien und
keinen laufenden Container. `test-backup-integration.py` erzeugt eine
Fixture-DB/Test-PNG und einen kurzlebigen GPG-Key, verschlüsselt das Bundle,
prüft die Offsite-Testkopie und den echten Restore in ausschließlich zufällig
benannten, isolierten Ressourcen; alle Ressourcen werden entfernt. Diese echte
Fixture ist Bestandteil von CI-FULL, nicht QUICK. Systemd-Units und Alarm-Hook
sind Vorlagen/Schnittstellen; ihre tatsächliche Wirkung wurde nicht live geprüft.

**Produktiv NICHT VERIFIZIERT:** Offsiteziel/Schlüsselverwaltung, Cron, freies
Backupvolumen, Betrieb unter Wartung, Wiederanlauf auf zweitem Host und
Alarmierung. Der Nutzer hat bestätigt, dass Offsiteziel und Alarmsystem noch
fehlen. Kein Produktivbackup, kein Reset und kein Produktiv-Restore wurden
ausgeführt. Auch dieser Paket-B1-Stand ändert daran nichts: es gab keinen
Live-Zugriff, keine Produktivmutation und kein Deployment. Rollback: alte
Release-Dateien bleiben bestehen; neue Sicherung ist ausdrücklich opt-in.
Ein Zurückrollen auf das alte SQL-Skript bietet weiterhin keinen Medien-Schutz.
