# Werkseinstellungen: Koordination und Wiederanlauf

Die Bestätigung bleibt exakt `Kompletter Reset`, mit aktuellem Passwort und einer
an die angemeldete Sitzung gebundenen, einmaligen Zwei-Minuten-Challenge. Das
Next-Gateway speichert die geprüfte Revision selbst; eine vom Browser eingesandte
Revision autorisiert nichts. Neue Vorschauen ersetzen ältere derselben Sitzung.

Ein PostgreSQL-Advisory-Lock schützt vollständige HTTP-Anfragen einschließlich
Upload-Dateizugriff und asynchronem Abschluss. Pro API-Prozess teilen höchstens
64 aktive Anfragen eine Koordinationsverbindung. Reset benötigt exklusiven Zugriff
und zusätzlich die bestehende Discovery-Sperre für den vollständigen Suchlauf.
Kalenderplanung nimmt den gemeinsamen Transaktions-Lock vor dem Lesen der Reihen.
Statement-Trigger schützen Schreibzugriffe auf alle 37 freigegebenen Fachtabellen.
Bei belegter Sperre beginnt kein Reset. Login und technischer Auth-Speicher bleiben
verfügbar und werden nicht zurückgesetzt.

Die Vorschau bindet Tabellenzählungen, eine Datenrevision und den Medienbestand.
Eine PostgreSQL-Sequenz vermeidet eine zentrale, konkurrierend aktualisierte
Zählerzeile. Auch zurückgerollte Schreibversuche können deshalb vorsichtshalber eine
neue Vorschau verlangen. Dateien werden anhand relativer Pfade, Dateityp, Größe,
mtime, Unix-ctime und Dateisystemidentität geprüft. Symlinks, unbekannte Tabellen,
fehlende Trigger, nicht kanonische Roots und überschrittene Bestandslimits brechen
vor der Löschung ab. Es gibt kein unbekannte Tabellen einbeziehendes `CASCADE`.

## DB-Commit und Dateien sind nicht atomar

Vor dem Datenbank-Commit schreibt die API im Medienroot ein privates Journal
`.noizy-reset.json` mit Modus 0600, atomarem Umbenennen und fsync für Datei und
Verzeichnis. In derselben Transaktion wie das Leeren der Fachtabellen speichert sie
Generation, SHA-256 des exakten Journals und bestätigte Datensatzzahl dauerhaft in
`maintenance_state`. Flyway, Auth-Speicher, diese Zustandszeile und die Revision
bleiben erhalten. Das OCR-Genrelexikon wird nach bestätigtem Commit aktualisiert.

Nur ein exakt zum bestätigten DB-Marker passendes Journal autorisiert Cleanup.
Es werden ausschließlich unveränderte ursprüngliche Einträge entfernt, nach
Prüfung aktueller Medienreferenzen. Neue, ersetzte und referenzierte Dateien bleiben
erhalten. Bei Unsicherheit oder Restbestand meldet die API ausdrücklich einen
Teilerfolg. Der ausstehende DB-Marker blockiert Fachzugriffe bis zum sicheren
Abschluss; Login bleibt erreichbar. Ein unklarer POST-Ausgang sperrt in der offenen
Browseransicht weitere Reset-Versuche auch nach Schließen und Wiederöffnen.

## Vorgehen nach Abbruch oder Teilerfolg

1. Keinen zweiten Reset anfordern. Release-SHA, Zeitpunkt, Antwortstatus und
   Meldung sichern; keine Passwörter, Schlüssel oder Journalinhalte veröffentlichen.
2. API-Logs auf bestätigten DB-Commit, Cleanup-Fehler und Wiederanlaufmeldung prüfen.
   Schreibzugriffe während der Diagnose geschlossen halten. Keine fremden
   Prozesse direkt in das Medienvolume schreiben lassen.
3. Ursache prüfen: Mount-/Dateirechte, Platz, Symlinks, veränderte Dateien,
   Medienreferenzen und Übereinstimmung von DB-Marker und privatem Journal.
   DB und Medien gehören zusammen; vor manueller Reparatur beide sichern.
4. Nach Behebung der Ursache startet die API den Wiederanlauf automatisch beim
   nächsten Start. Ein gültiger bestätigter Intent wird erneut abgeglichen.
   Fehlendes, beschädigtes oder abweichendes Journal schließt den Zugriff sicher
   und erfordert eine konkrete Administratorprüfung. Marker oder Journal niemals
   pauschal löschen und keine neuen Dateien in einen alten Reset aufnehmen.
5. Fehlt ein bestätigter DB-Marker, entfernt der Wiederanlauf ausschließlich das
   gültige verwaiste Journal, keine Anwendungsdateien. Das gilt sowohl für einen
   Abbruch vor Commit als auch nach bereits bestätigtem Cleanup. Zwischenzeitlich
   neu angelegte Daten bleiben erhalten.

Auf einem anderen Dateisystem wiederhergestellte Dateien besitzen andere Inodes
und Änderungszeiten. Der konservative Vergleich behält sie daher bei; Backup-Restore
ist keine automatische Freigabe zur Dateilöschung. Externe Dateisystemadministration
muss für Diagnose und Cleanup koordiniert bleiben.

Migration V25 wird nicht zurückgerollt oder nachträglich umgeschrieben. Ein älterer
Reset verweigert wegen der zusätzlichen Infrastruktur-Tabelle die Schemafreigabe.
Bei einem Releaseproblem vorwärts korrigieren oder den dokumentierten Release-
Rollback nutzen; keine Tabellen zur Anpassung an alten Code entfernen.

## Nachweise und Produktionsgrenze

Die Regression läuft ausschließlich in einer zusätzlich angelegten, anschließend
entfernten Testdatenbank und `target/test-factory-reset-media`. Sie umfasst echte
parallele Upload-/Kalenderanfragen, Discovery-Sperre, veraltete Vorschau, ursprüngliche
Zugangs-/Schema-Guards, einen mit `Runtime.halt` nach Commit beendeten separaten
JVM-Prozess und Wiederanlauf in einer neuen JVM. Negative Wiederanläufe bewahren
neue/ersetzte Dateien sowie fehlende oder manipulierte Journale. Unit-Tests prüfen
Dateipfade, Metadaten, Sperrverlust, begrenzte Verbindungen und asynchronen Abschluss.

Die bestehende Chromium-Testumgebung prüft die UI bei 1440 und 390 Pixeln in drei
Themes und zwei Dichten ausschließlich mit abgefangenen lokalen API-Fixtures:
Doppel-Submit-Sperre, deaktivierte Bestätigung während der Anfrage, neue vollständige
Bestätigung nach Revisionkonflikt, wahrheitsgemäßer Teilerfolg ohne falsche
Dateifehlermeldung, Fokus und dauerhaft gesperrter unbekannter Ausgang.

Produktionsabnahme: nur Erreichbarkeit, Authentifizierung, Readiness und lesende
Vorschau prüfen. **Kein Reset in Produktion oder Stage, auch nicht als Smoke-Test.**
Der leere Resetfall und Crash werden ausschließlich isoliert getestet. CLI-Gates
belegen keine nicht zugänglichen Live-IDE-Inspektionen.
