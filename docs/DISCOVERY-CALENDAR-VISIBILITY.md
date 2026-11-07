# Discovery: Sichtbarkeit übernommener Kalendertermine

## Bestandsaufnahme und Codeprüfung (06.11.2026)

Der nach dem Systemabsturz erhaltene Arbeitsstand auf
`fix/discovery-calendar-visibility` basiert auf `172e220`. Das lokal gespeicherte
`origin/main` enthält zusätzlich `cbe4598` und dessen Merge `6690a04`.
Keine Branch-Historie geändert, keine bestehenden Änderungen zurückgesetzt.

Vorhanden sind der aktuelle Berliner Kalendermonat, Nachladen und Aktualisieren,
Links zum konkreten Kalendertermin aus Discovery, Schutz vor wiederholter
Bestätigung und ein Importzähler mit Join auf vorhandene Kalendertermine.
Frontend- und Backend-Regressionen liegen im Arbeitsstand vor.

Die temporären `.next/calendar-visibility`-Includes wurden entfernt: Die aktuelle
Next-Konfiguration verwendet kein entsprechendes `distDir`. Die übrigen
TypeScript-Einstellungen entsprechen unverändert dem bisherigen Commit.

V19 ergänzt den bestehenden Fremdschlüssel mit `ON DELETE SET NULL` um einen
BEFORE-UPDATE-Trigger: Ein Import ohne Kalenderreferenz wird auf `NEEDS_REVIEW`
und `MANUAL` gesetzt. Rückfragedaten bleiben erhalten; der bisherige Grund wird
in den Prüfhinweis übernommen. Derselbe Weg repariert historische Importe ohne
Referenz. Eine Check-Constraint verhindert neue importierte Zustände ohne
Kalenderreferenz. Scan und Neubewertung schützen manuelle Entscheidungen;
automatischer Import verlangt weiterhin `AUTOMATIC` und `READY`.
Die gezielten Datenbankprüfungen hierzu sind im folgenden Abschnitt ausgewiesen.

## Tatsächlich ausgeführte Prüfungen und Abbruch

- `git diff --check`: erfolgreich.
- Gezielter Frontendlauf mit `node --test --test-concurrency=1` für
  `calendar-navigation.test.mjs` und `discovery-assessment.test.mjs`:
  Die drei Kalendernavigationstests bestanden. Die Discovery-Testdatei wurde
  wegen Speicherdruck abgebrochen; kein erfolgreicher Gesamtlauf.
- Vor dem Lauf waren ungefähr 10,7 GB RAM verfügbar. Trotz begrenztem
  Node-Heap und nur einem Testworker wuchs der Discovery-Worker auf über 8 GB
  RSS. Eine externe Speicherüberwachung beendete die Prozessgruppe bei weniger
  als 2 GB verfügbarem RAM (Exit 75). Kein automatischer Wiederholungsversuch.
- Die inkrementelle Backend-Kompilierung wurde versehentlich gestartet, während
  der Frontendlauf noch aktiv war. Sie wurde nach Erkennen dieser Überschneidung
  beendet (Exit 143); sie gilt nicht als bestanden.
- Keine Datenbanktests, vollständigen Modulprüfungen, Builds oder Browserprüfungen.
  Kein neuer Testcontainer und keine Änderungen an bestehenden Datenbanken.

## Gezielter Abschluss nach der Fehleranalyse

Der Speicheranstieg ließ sich auf eine veraltete DOM-Assertion eingrenzen:
Nach erfolgreicher Bestätigung erwartete der bestehende Test einen geschlossenen
Dialog, obwohl die vorhandene Änderung den Kalenderlink im geöffneten Dialog
anbietet. Die Fehlerausgabe des tatsächlichen DOM-Objekts verursachte den starken
Speicheranstieg. Der Test prüft jetzt den konkreten Link und die gesperrte erneute
Bestätigung. Keine Produktfunktion wurde zusätzlich erweitert.

Die Diagnose verwendete Node 24 statt des zuvor aktiven Node 26, einen CPU-Kern
für nacheinander laufende Node-Testdateien sowie begrenzte Java-/Node-Heaps.
Eine externe Überwachung begrenzt die gesamte Prüfprozessgruppe auf 2 GB RSS
und stoppt zusätzlich unter 4 GB verfügbarem RAM. Diagnoseprozesse wurden an der
Prozessgrenze beendet, ohne erneut den Arbeitsspeicher des Rechners auszuschöpfen.

Tatsächlich erfolgreich nach der Testkorrektur:

- `node --test --test-concurrency=1 frontend/tests/calendar-navigation.test.mjs
  frontend/tests/discovery-assessment.test.mjs`: 38 Tests, keine Fehler oder
  übersprungenen Tests; mindestens 10493 MiB RAM verfügbar.
- Inkrementelles Maven-`test-compile` ohne Paketierung: erfolgreich.
- Zusätzliche SQL-Prüfungen von V19 gegen synthetische Tabellen in eigenem Schema
  innerhalb einer zurückgerollten Transaktion: historische Reparatur, unveränderte
  gültige/manuelle und abgelehnte Entscheidungen, Rückfragedaten, Löschen mit
  `ON DELETE SET NULL`, Check-Constraint und Fremdschlüssel erfolgreich.
- Maven-`surefire:test -Dtest=DiscoveryIntegrationTests`: 22 Tests bestanden,
  keine Fehler und keine übersprungenen Tests. Einschließlich Kalender-API,
  Importzähler, Entfernen eines importierten Termins und Schutz vor Wiederimport.
  Die tatsächlichen Flyway-Migrationen liefen in einer ausschließlich hierfür
  gestarteten PostgreSQL-17-Testdatenbank ohne bestehende Volumes, mit zufälligem
  Loopback-Port und eigenen Zugangsdaten. Container auf 384 MB begrenzt und
  anschließend entfernt. Mindestens 10247 MiB RAM verfügbar.
- `git diff --check`: erfolgreich.

Der erste QUICK-Commitversuch wurde durch eine ECJ-Null-Type-Safety-Warnung
am Hamcrest-Matcher im neuen Integrationstest blockiert. Ein explizites
`requireNonNull` prüft diese unannotierte Bibliotheksgrenze jetzt zur Laufzeit.
Die betroffene Testmethode wurde danach erneut gegen eine frische isolierte
Testdatenbank erfolgreich ausgeführt (ein Test, keine Fehler/Skips; Container
entfernt, mindestens 10158 MiB RAM verfügbar). Die übrigen 21 unveränderten
Integrationstests wurden nicht wiederholt. Die frischen Compileranalysen laufen
mit dem regulären Hook erneut.

Die ersten Hook-Schritte meldeten außerdem 401-Warnungen für Metadaten zweier
externer Maven-Repositories. Der Abschluss verwendet den vorhandenen vollständigen
Dependency-Cache offline (`MAVEN_ARGS=-o`), ohne Prüfungen zu überspringen.
Die Datenbankläufe meldeten den üblichen JVM-Hinweis zur eingeschränkten
Class-Data-Sharing-Unterstützung bei Mockito; keine Java-Compilerdiagnose.

Der nächste Hook bestätigte javac/ECJ ohne blockierende Diagnosen, TypeScript
und 119 Backendtests (118 erfolgreich; ausschließlich der dokumentierte optionale
historische Offline-Test ohne Exportdatei übersprungen). ESLint blockierte danach
den neuen Testloader wegen des reservierten Variablennamens `module`. Die lokale
Variable heißt jetzt `loaded`; die drei Kalendernavigationstests und gezieltes
ESLint für diese Datei bestanden danach. Keine Lintregel wurde abgeschwächt.

Die gemeinsame Frontendsuite erreichte anschließend 81 Tests und meldete fünf
Kalenderfehler im bestehenden `dashboard-modal.test.mjs`: Der Testloader stellte
weder den neu benötigten Navigationskontext noch die Nachladefunktionen bereit.
Zwei Fokusprüfungen setzten außerdem weiterhin Januar 2027 als Anfangsmonat voraus.
Die Mocks wurden ergänzt; nur diese zwei Kalendertermine verwenden jetzt den
aktuellen Berliner Tag. Die 34 Tests dieser Datei und ihr gezieltes ESLint
bestanden danach; mindestens 10192 MiB RAM verfügbar. Die Produktimplementierung
wurde hierfür nicht geändert.

Der Abschlusscommit wird ausschließlich über den unveränderten regulären
QUICK-Commit-Hook erstellt; dessen konkrete Compiler-, Analyse- und Testergebnisse
stehen im Hook-Protokoll unter `.quality/run.*/`. Kein vorheriger manueller
QUICK-Lauf und keine vollständigen Modulprüfungen, Produktionsbuilds oder
Browserprüfungen. Bestehende Datenbanken wurden nicht verwendet oder verändert.

FULL bleibt vor Integration für den exakt akzeptierten Commit in GitHub CI
verpflichtend. CLI-Ergebnisse sind kein Nachweis der Live-IDE-Diagnosen.
Kein eigenständiger Merge oder Deployment. Der Feature-Branch behält seine
bestehende Historie; die beiden neueren main-Commits wurden nicht übernommen.

## Kalender-Tagesvorschau (07.11.2026)

Feature-Branch `feat/calendar-day-preview`, ohne neuen Worktree auf aktuellem
`origin/main` begonnen; der Arbeitsbaum war sauber. Ausschließlich Frontend:
Die Monatszellen behalten maximal zwei Terminkennzeichnungen und bieten für
jeden belegten Tag „Alle X ansehen“. Raster und Vorschau verwenden dieselben
vorhandenen Such-/Art-/Locationfilter. Das vorhandene DashboardModal zeigt alle
Tageseinträge, stabile Uhrzeitsortierung mit unbekannten Zeiten am Ende,
Partyfarben, Locations, Absagen und getrennt bezeichnete Location-Sperren.
Auswahl schließt die Vorschau und verwendet die vorhandenen Flyerdetails.

Ein gemeinsamer ref-basierter Timer für den Monat öffnet nach 400 ms Maus-Hover
mit passender Gerätefähigkeit beziehungsweise 550 ms primärem Touch-/Pen-Druck.
Verlassen, Bewegung über acht Pixel, Scrollen, Pointer-Abbruch/-Loslassen,
Fensterblur, Ansichts-/Filter-/Datenwechsel und Unmount verwerfen wartende Timer.
Nach dem Öffnen bleibt das Modal erhalten; eine geometrische Sperre verhindert
erneutes Hover-Öffnen über demselben Tag nach dem Schließen. Erkannte Long-Presses
unterdrücken ihren Folgeklick/Doppelklick und ihr Kontextmenü, ohne native
Scrollgesten oder direkt angetippte Termine umzubelegen. Tagesauswahl und
Vorschauaktion sind eigenständige Buttons innerhalb einer nicht interaktiven
Gruppe. Keine zusätzlichen API-Aufrufe oder Bibliotheken.

Gezielte jsdom-Prüfung: zunächst 49 von 50 Tests bestanden; der bestehende
Editor-Test verwendete einen zu weit gefassten Selektor für gedrückte Buttons.
Dieser ist jetzt auf den tatsächlichen Dialog begrenzt. Die ersten acht neuen
Gesten-/Vorschautests bestanden auch im Folgelauf. Der zusätzliche Filtertest
verwendete eine nicht vorhandene Select-Option. Die anschließende fehlgeschlagene
Assertion gab ein umfangreiches React-DOM-Objekt aus; der Speicherwächter brach
bei 2.420 MiB Prozessgruppen-RSS und noch 6.627 MiB verfügbarem System-RAM ab
(Exit 75). Kein automatischer Neustart. Auswahloption und Assertion-Ausgaben sind
korrigiert; die gezielten Prüfungen sowie TypeScript/ESLint und der reguläre
QUICK-Commit-Hook wurden bis zur ausdrücklichen Freigabe angehalten.
Prüfprotokolle: `.quality/calendar-visibility/day-preview-*.log`.
Keine Builds, Browser, Backend-/Datenbankänderungen, eigenen Merges oder Deployments.

Nach ausdrücklicher Freigabe gezielt fortgesetzt: alle neun neuen Vorschautests
bestanden (`node --test --test-concurrency=1 tests/calendar-day-preview.test.mjs`).
Danach zwölf bestehende Kalender-/Fokus-/Navigationsregressionen bestanden
(`node --test --test-concurrency=1 --test-name-pattern='calendar|Kalendereinträge'
 tests/calendar-navigation.test.mjs tests/dashboard-modal.test.mjs`). Beide Läufe
seriell mit Speicherprüfung und laufendem Wächter; nach Wiederaufnahme mindestens
8.667 MiB verfügbar, kein weiterer Speicherabbruch. Keine vorher bestandenen
unbetroffenen Testfälle zusätzlich manuell wiederholt. Der abschließende reguläre
QUICK-Commit-Hook umfasst die nötigen Typ-/ESLint-/Frontendprüfungen; kein manuell
vorweggenommener QUICK-Lauf. FULL bleibt für den akzeptierten Commit GitHub CI
vorbehalten. jsdom und CLI-Prüfungen ersetzen keinen Nachweis realer Touchgeräte
oder der Live-IDE-Diagnosen.
