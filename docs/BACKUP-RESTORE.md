# Backup/Restore: BKP-01 / BKP-02

## Entscheidung und Sicherheitsgrenze

Ausgangspunkt `main` 160cd81: `backup.sh` erzeugte nur SQL, benötigte exportierte
Shellvariablen und sicherte keine Medien/Wiederanlaufkonfiguration. Im SHA-Release-
Layout liegt die persistente `.env` außerhalb des Skriptverzeichnisses. Ein SQL-Dump
allein ist kein wiederherstellbarer Anwendungsstand.

Das neue Werkzeug verwendet explizite Compose-Datei, persistente `.env` und
Projektname. Docker Compose parst die Konfiguration; niemals `source .env`.
Zugangsdaten bleiben im DB-Container und gelangen nicht in Host-Prozessargumente
oder Logs. Die GPG-Empfängeridentität wird ausschließlich als expliziter
Schlüsselfingerprint akzeptiert, ohne automatischen Schlüsselabruf.

**Konsistenzvoraussetzung:** API und Frontend müssen bereits gestoppt sein.
Das Werkzeug stoppt/startet selbst keine Dienste. Pausierte/laufende Dienste und
andere laufende Container mit schreibendem Medienmount führen zum Abbruch.
DB-Fakten und Medienprüfsummen werden vor/nach der Sicherung verglichen. Während
der Wartung dürfen auch Administratoren, Cronjobs oder externe Prozesse keine
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
`--allow-empty-media`. Keine partiellen Generationen bei Dump-/Archiv-/GPG-Fehlern.

## Operatorablauf – kein automatisch ausgeführtes Deployment

Voraussetzungen: Python 3.11+, Docker mit Compose v2, GnuPG; bestehende geprüfte
PostgreSQL-Image-ID verfügbar. Serverdateien müssen aus demselben qualifizierten
Release stammen. Verschlüsselungskey separat/offline sichern; ohne privaten
Schlüssel gibt es keine Wiederherstellung. Das Werkzeug ändert keine Schlüssel.

1. GPG-Public-Key auf dem Sicherungshost installieren, Fingerprint außerhalb dieses
   Kanals prüfen. Privaten Wiederherstellungsschlüssel auf getrenntem Testhost
   bereitstellen. Nie Passphrasen als Argument oder in Git speichern.
2. Separat freigegebenes Wartungsfenster nutzen, Schreibdienste mit dem aktiven
   Compose-Manifest kontrolliert stoppen; kein `down -v`, keine Volume-Löschung.
   Dies ist **keine** Freigabe, den laufenden VPS durch Codex zu stoppen.
3. Sicherung starten (Pfad und Fingerprint durch eigene Werte ersetzen):

   ```sh
   NOIZY_SERVER_DIR=/opt/noizycologne/deploy/server \
     bash /opt/noizycologne/deploy/server/releases/<SHA>/backup.sh \
     --output /geschuetzt/backups --recipient <PUBLIC-KEY-FINGERPRINT>
   ```

   Ein vorhandenes Ausgabe-/Offsiteverzeichnis muss Modus `0700` besitzen.
   Geheime `.env`, Dump und Medien liegen nur während der Erzeugung im privaten
   temporären Arbeitsverzeichnis unverschlüsselt vor; ausreichend freien lokalen
   Speicher und einen verschlüsselten Datenträger verwenden. Temporäres wird
   bei Erfolg/Fehler entfernt. Stromausfall-/Datenträger-Recovery bleibt Betrieb.
4. Nur die fertige `.tar.gpg` übertragen. `--offsite-dir /geschuetzt/offsite`
   unterstützt ein **bereits eingerichtetes**, zugriffsgeschütztes Ziel/Mount und
   prüft die kopierten Bytes. Bei fehlendem Ziel lautet der Status ausdrücklich
   `NOT_CONFIGURED`; ein lokales Backup ist kein Offsite-Erfolg. Ein Kopierfehler
   liefert Exit 1 und erhält die bereits gültige lokale Generation. Transport,
   Verfügbarkeit und Retention des externen Ziels bleiben Betreiberverantwortung.
5. Schreibdienste nach Sicherung/Fehler kontrolliert mit bisherigem Release
   wieder starten und Health/Login-Smoke prüfen. Das Backupwerkzeug aktiviert
   keine Dienste und kann keinen automatischen Neustart als Erfolg vortäuschen.
6. Statusdatei meldet Dateigröße, SHA256, Medienanzahl, Offsitezustand. Alter über
   Sicherungszeit und Dateimetadaten überwachen; keine Inhalte protokollieren.

## Isolierter Restore-Nachweis

```sh
bash deploy/server/restore-test.sh /geschuetzt/noizycologne-<generation>.tar.gpg
```

Das Werkzeug entschlüsselt in ein privates temporäres Verzeichnis, prüft Manifest,
Payload-/Medienhashes und Wiederanlaufkonfiguration. Es erstellt ausschließlich
zufällige `noizy-restore-*`-Ressourcen: neue DB, neue Volumes, eigenes Passwort,
kein Netzwerk und keine veröffentlichten Ports. Bestehende Ziele, geerbte DSNs und
angegebene Produktivvolumes können nicht als Restoreziel verwendet werden.
`pg_restore` läuft ohne `--clean` in die leere Instanz. Tabellenzählungen,
Flyway-Versionen/Checksummen/Erfolg und Medienreferenzen werden verglichen;
Medien werden in ein neues Volume entpackt und erneut gehasht. DB/Medien-Testziele
werden anschließend entfernt. Rückgabestatus `VERIFIED_FIXTURE_TARGET` bestätigt
nur diesen Restore, keine Inbetriebnahme oder einen echten Login mit Produktivdaten.

Die im Manifest enthaltene PostgreSQL-Image-ID muss lokal verfügbar sein; das
Werkzeug zieht keine unbekannten Images nach. Für einen anderen Wiederanlaufhost
muss der Betreiber das geprüfte Image separat verfügbar halten und die ID prüfen.
Eine vollständige Wiederanlaufprobe umfasst anschließend – separat freigegeben –
Appversion, Login, Bildanzeige und Secret-/Volumezuordnung im isolierten Zielnetz.
Nie wiederhergestellte Testdaten direkt auf Produktivvolumes kopieren.

Retention ist ein eigener expliziter Aufruf:

```sh
python3 deploy/server/backup.py prune --output /geschuetzt/backups
```

Nur unveränderte Generationen mit verifiziertem Offsite-Kopiestatus werden nach
14 Tagen entfernt; die zwei neuesten bleiben immer erhalten. Lokale-only,
beschädigte oder unbekannte Dateien werden nicht automatisch gelöscht. Die
Retention ersetzt weder Restoreprüfungen noch externen Schutz vor Löschung.

## Nachweise und Grenzen

`python3 -m unittest discover -s scripts/tests -p test_quality_backup.py -v`
prüft Fehler/Vertraulichkeit, fehlende Umgebung, Medienpfade/Referenzen,
Teilfehler, Offsite-Kopie und Retention. `python3 scripts/test-backup-integration.py`
erstellt nur Fixture-DB/Test-PNG und einen kurzlebigen GPG-Key, verschlüsselt das
Bundle, prüft die Offsite-Testkopie und den echten Restore; alle Ressourcen werden
entfernt. Diese echte Fixture ist Bestandteil von CI-FULL, nicht QUICK.

**Produktiv NICHT VERIFIZIERT:** Offsiteziel/Schlüsselverwaltung, Cron, freies
Backupvolumen, Betrieb unter Wartung, Wiederanlauf auf zweitem Host und Alarmierung.
Der Nutzer hat bestätigt, dass Offsiteziel und Alarmsystem noch fehlen. Kein
Produktivbackup, kein Reset und kein Produktiv-Restore wurden ausgeführt. Rollback:
alte Release-Dateien bleiben bestehen; neue Sicherung ist ausdrücklich opt-in.
Ein Zurückrollen auf das alte SQL-Skript bietet weiterhin keinen Medien-Schutz.
