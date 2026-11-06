# Performance und Multi-Source-Discovery – Arbeitsnachweis

Basis: `b985e68`, bereits geprüfte JavaScript-Discovery plus Quality-Strategie.
Feature-Branch `feat/multi-source-discovery`; keine Produktionsdatenänderung,
kein Merge nach main, kein Deployment. Bestehende lokale Upload-Änderungen bleiben
im ursprünglichen Worktree erhalten. Die Umsetzung nutzt vorhandene Dienste und
Tabellen statt zusätzlicher Infrastruktur.

## Phase 1 – Entwicklungsworkflow

Der Ausgangsstand besitzt bereits QUICK-Hook, Feature-QUICK, PR-/main-FULL,
SHA-gebundene Freigaben vor GHCR/Deployment, Smoke-Test/Rollback und Dependency- /
BuildKit-Caches. Diese geprüften Funktionen bleiben erhalten. Ein vorheriger
vollständiger Lauf für `b985e68` dauerte rund vier Minuten; wiederholte manuelle
QUICK-/FULL-Läufe unmittelbar vor dem Hook verursachten vermeidbare Mehrarbeit.
Weitere Kosten: Java-Negativtests ca. 50 Sekunden und Gate-Selbsttests ca. 40–50
Sekunden bei Werkzeugänderungen, Datenbankstart ca. 15 Sekunden plus Integration.

Änderungen: QUICK kompiliert/analysiert alle aktuellen Java-Testquellen, führt aber
nur ausgewählte Unit-Tests aus und nennt zurückgestellte Datenbanktests ausdrücklich.
Keine vollständige Docker-Datenbankumgebung im Commit-Hook. STANDARD-Backend nutzt
sichere inkrementelle Kompilierung; FULL bleibt sauber und vollständig. Löschungen
und gemeinsame Compilerinputs erzwingen weiterhin frische Ausgaben. Agent-Regeln
fordern gezielte Wiederholungen, kleine Aufgaben und das Vermeiden doppelter
unveränderter Komplettprüfungen. Es gibt keine Ergebnis-Zertifizierung aus alten
Caches oder geänderte Warnungsregeln.

Prüfungen: Scope-/Workflow-Fixtures prüfen Unit-Auswahl, ausdrücklich zurückgestellte
Integration, STANDARD mit eigener Testdatenbank, Fehler-/Cleanup-Propagation,
Indexschutz und unveränderte FULL-Pflicht. Aktuelle Laufzeiten liegen pro Lauf in
`.quality/run.*/timings.json`; QUICK und FULL haben ausdrücklich unterschiedlichen
Prüfumfang. Ein messbarer Zeitvergleich folgt mit dem tatsächlichen Abschlusslauf.

GitHub wurde lesend geprüft: main meldet `protected: false`, Repository-Rulesets
sind leer (06.11.2026). Damit ist die CI-Freigabe vor dem Deployment technisch
vorhanden, aber ein ungeprüfter Merge noch nicht zuverlässig ausgeschlossen.
Die bestehende Anleitung und Sollkonfiguration in `docs/QUALITY.md` und
`.github/branch-protection-main.json` müssen durch einen Repository-Administrator
aktiviert werden; keine Einstellungen wurden ungefragt verändert.

Grenze: QUICK ersetzt Integrationstests nicht. Umfangreiche Backendänderungen
benötigen STANDARD, PR/Integration/Freigabe benötigt FULL für den akzeptierten
Commit. IDE-Zugriff und erfolgreiche CLI-Diagnosen sind getrennt auszuweisen.

## Phase 2 – einheitliches Quellenmodell

V16 ergänzt die bestehende Quellentabelle um Art (Website, offizieller Veranstalter,
offizielle Tickets, Plattform, sonstige Quelle), Aktivierung, Priorität, fachliche
Abrufqualität, Eventzahl und Fallbackkennzeichnung. Bestehende URLs, Profile und
Freigaben bleiben erhalten. Ungeprüfte Altquellen werden nicht nachträglich als
erfolgreich ausgegeben. Die Location erhält einen getrennten letzten Versuch und
Qualität/Zähler. Keine neue Infrastruktur oder konkurrierende Quellenverwaltung.

`SourceQualityTests` prüfen die fünf Qualitätsklassen mit statischer vollständiger
Liste, bestätigtem Leerbestand, unbekanntem HTML sowie einer MTC-artigen
unvollständigen Browserantwort. Quellenkonfiguration wird unter demselben
Transaktionslock wie Import/Prüfentscheidungen gespeichert. Vorhandene API-Aufrufe
ohne neue Felder bleiben kompatibel. Quellenherkunft ist aus dem bestehenden
Beobachtungsprotokoll abrufbar; Migration und Persistenz werden mit dem nachfolgenden
Integrationstest in einer eigenen Testdatenbank geprüft, nicht in QUICK behauptet.

## Phase 3 – konfigurierte Fallbacks und Herkunft

Der bestehende Scheduler untersucht primäre Website/Eventübersicht und bis zu acht
konfigurierte Quellen in Prioritätsreihenfolge; nach Fehlern wird fortgesetzt.
Bereits während desselben Crawls geprüfte URLs brauchen ohne besonderes Profil
keinen zweiten Abruf. Deaktivierte Quellen werden nicht verwertet. Es werden keine
fremden Domains geraten. Zusätzliche konfigurierte Quellen dürfen auch bei
brauchbarer Primärquelle ergänzen; eine vorausgegangene unvollständige Quelle wird
als Fallback-Anlass festgehalten. Seiten-/Event-/Browserbudgets bleiben begrenzt.

Ein gemeinsamer Batch kombiniert nur belastbar zugeordnete Vorkommen: passender
Titel, gleicher Tag und gebundene Location oder passende stabile Eventidentität.
Eine ungebundene externe Liste oder abweichende Location darf keine Genres leihen.
Unterschiedliche Tage/Uhrzeiten bleiben Konflikte; klar verschiedene stabile
Wiederholungstermine bleiben getrennt. Ein Datum ohne Uhrzeit kann durch eine
belegte Uhrzeit ergänzt werden, ohne eine zu raten. Ungeprüfte externe Absagen
werden nicht zu offiziellen Bestätigungen.

Originalbeobachtungen bleiben pro Quelle im vorhandenen Beobachtungsprotokoll
abgelegt. Änderungsvergleiche benutzen den letzten Stand derselben Quelle;
zusätzliche passende Genreinformationen und wiederholte identische Abrufe sind
keine fingierten Änderungen. Kalender-/Redaktionsschutz und zentraler
Cancellation-Prozess bleiben erhalten. Unit-Fixtures prüfen Zusammenführung,
abweichende Location, ungebundene Plattform, Datum-/Zeitkonflikte, Wiederholungen
und Herkunft. Die nachfolgende Datenbankintegration prüft Persistenz und idempotente
Wiederholung; QUICK behauptet diese Prüfung nicht.

## Phase 4 – fachliche Integration

Gemäß nachgereichter Festlegung ist der Nenner der 50-%-Schwelle **die gesamte
Anzahl aktiver Projektgenres**, nicht die Anzahl im Event genannter Genres.
Jedes aktive Genre zählt einmal; kanonischer Name, Anzeigename und Aliasse
identifizieren dasselbe Genre. Quellen-URLs allein zählen nicht als Genrebeleg.
Ohne aktive Genres gibt es keine Portfolioempfehlung. Bekannte Reihen behalten
die vorhandenen Importregeln; die Schwelle betrifft neue Portfolioaufnahmen.

Die ausdrückliche Portfoliofreigabe verwendet PartySeriesService und bestehende
lesbare Standardgenres. Der belegte Eventtitel wird als Alias gespeichert; eine
Wiederholung derselben Freigabe erzeugt keine zweite Reihe. Geschützte Ablehnungen,
Zuordnungen, Absagen und offene Änderungen blockieren die Aktion. Nur ein vorher
unverändert automatisch geprüfter Kandidat mit belegtem Datum aus freigegebener
Quelle kann im selben bestätigten Schritt in den Kalender gelangen. Sonstige
Terminkorrekturen bleiben manuell. Der normale Kalender-/Redaktionsweg bleibt
erhalten; es werden keine gespeicherten Entwürfe verändert oder Instagram-Aktionen
aufgerufen.

Eine ausdrücklich angegebene relative Beginnzeit „nach dem Konzert“ bleibt als
Text in den Kalendernotizen; start_time bleibt NULL. Es wird keine Uhrzeit aus
Konzertdaten erfunden. Das Konzert selbst bleibt ausgeschlossen; eigenständige
Aftershow-Partys werden separat bewertet.

Regressionen: echte isolierte PostgreSQL-Datenbank mit MTC-artigem Teilrender und
zwei konfigurierten Ersatzquellen, ergänzte Genres/Uhrzeit und Originalherkunft,
wiederholte Scans ohne zweite Kalenderzeile oder falsche Änderung, Datumskonflikt,
geschützte Ablehnung, deaktivierte Quelle, einmalige Portfoliofreigabe,
offizielle Ticketabsage im bestehenden Workflow und relativer Aftershow-Beginn.

## Phase 5 – Oberfläche und Abschluss der Workflow-Eingrenzung

Location-Bearbeitung zeigt auf Anforderung denselben verständlichen Quellenstatus
wie „Vorgeschlagene“. Pro Quelle: Qualität, gezählte Funde, letzter Versuch/Erfolg,
Freigabe und Fallbacknutzung. Technische HTTP-/Browser-/Vollständigkeitsdiagnosen
bleiben unter „Technische Details“. Eine Teilauswertung mit null Funden heißt
nicht „leerer Kalender“. Quellenart, Aktivierung und Priorität werden im
bestehenden Quellenmodal verwaltet; keine zweite Verwaltung und keine gestapelten
Modals. Im Kandidatenmodal können Originaltitel, Datum, Location und Quellenbeleg
verglichen werden. Neue Reihen ab 50 % aller aktiven Projektgenres benötigen die
sichtbare einmalige Portfoliofreigabe. Ein relativer Beginn erscheint nur bei
neu vorbereiteten Captions; gespeicherte Captiontexte bleiben unverändert.

DOM-Regressionen bedienen die echten Komponenten: 50-%-Freigabe gegenüber
25-%-Nichtfreigabe, expliziter Portfolioaufruf ohne Publishing, MTC-Teilauswertung,
Quellenart/Priorität/Fokus und lesbarer Quellenvergleich. Compiler-/ECJ-Analysen
bleiben strikt; alle durch die Änderung aufgefallenen Null-/Conversion-Diagnosen
werden korrigiert. STANDARD wiederholt Tests der Analysewerkzeuge nur bei deren
Änderung; FULL enthält weiterhin sämtliche negativen Compiler-/Frontend-Fixtures
und Gate-/Deployment-Regressionen. Es entstehen keine zusätzlichen Testwerkzeuge.

### Aktuelle Prüfung und Grenzen

STANDARD-Backend lief erfolgreich: **175 Tests, 174 bestanden, ein optionaler
historischer Offline-Test ohne Export übersprungen**, dazu striktes javac, ECJ
mit null Problemen einschließlich INFO, Java-Negativfixtures und Backend-Paket.
Die sechs neuen PostgreSQL-Integrationsfälle bestanden. Die aktuellen 13
Discovery-DOM-Tests einschließlich der vier neuen Multi-Source-/Portfoliofälle
bestanden. IDE-Diagnosen sind nicht aus diesen CLI-Ergebnissen abgeleitet.

Gemessene QUICK-Läufe: reine Werkzeugänderung **46,12 s**, Quellenmodell
**35,79 s**, Quellenkombination **34,86 s**, Backend plus UI-Zwischenstand
**51,41 s**. Der vorherige FULL-Lauf auf Basis `b985e68` dauerte **254,21 s**.
Dies sind bewusst unterschiedliche Prüfumfänge und keine behauptete Beschleunigung
desselben FULL. Ein STANDARD-Backendlauf vor der zusätzlichen Eingrenzung
unveränderter Werkzeugtests benötigte **189,77 s**; die 11 echten Java-
Negativfixtures und Gate-Selbsttests waren darin noch enthalten. Keine künstliche
Abbruchzeit und keine Freigabe aus alten Ergebnissen.

Es wurde kein neuer Live-Abruf als Regression verwendet. Die MTC-artige Fixture
bildet den zuvor separat diagnostizierten unvollständigen Browserabruf nach;
konfigurierte, kontrollierte Zusatzquellen beweisen den Fallback. Dies behauptet
keinen aktuellen Live-Erfolg für MTC. Reale geeignete Zusatzquellen müssen vom
Team konfiguriert/freigegeben werden. Genrekataloggröße beeinflusst bewusst die
50-%-Schwelle. Titel-/Datums-/Locationvergleich bleibt konservativ; ungebundene
Plattformfunde und widersprüchliche Angaben erfordern Prüfung. Usernamespace-/
Browserlimits und Produktionsvoraussetzungen aus BROWSER-CRAWLER.md bleiben
unverändert. Visuelle Mobil-/Theme-Abnahme, GitHub-main-Schutz und die spätere
CI-Freigabe des integrierten Commits stehen weiterhin aus.


Abschließende betroffene Prüfungen: **39 Frontend-DOM-Tests** im regulären QUICK-
Hook, TypeScript und ESLint mit null Warnungen; Frontend-Produktionsbuild erfolgreich.
Nach der Ergänzung offizieller Veranstalter-/Ticketbestätigungen wurden gezielt
**19 Monitoring-/Multi-Source-Integrationstests** in einer neuen eigenen
Testdatenbank wiederholt, einschließlich Backend-Paketierung; alle erfolgreich.
Eine zusätzliche Budgetregression schützt ergänzende Genres davor, durch einen
langen Primärtext aus dem begrenzten kombinierten Beleg verdrängt zu werden;
Originalbelege bleiben vollständig innerhalb der vorhandenen Event-Beleggrenzen
im Beobachtungsprotokoll erhalten. FULL wurde auf diesem Feature-Branch nicht
unnötig wiederholt; vor Merge muss CI den tatsächlich integrierten Commit vollständig
freigeben. Der letzte gemischte QUICK-Lauf einschließlich geänderter Werkzeugtests
und UI benötigte **94,81 s**; die kleineren Phasenschritte blieben bei 35–51 s.

Ein gezielter zusätzlicher Failover-Fall prüft eine erst später ausgefallene
Primärquelle: Bei unveränderten belegten Eventfakten und ohne offenen
Änderungsbefund bleibt der importierte Termin erhalten. Bloß unterschiedliche
Quellenbelegtexte führen dabei nicht erneut zu einem Prüfstatus. Datum-, Uhrzeit-,
Location-, Lifecycle-Konflikte und geschützte manuelle Entscheidungen bleiben
von dieser engen Ausnahme ausgeschlossen.

Diese abschließende Auswahl bestand: **sieben Multi-Source-Integrationstests**
(einschließlich späterem Primärquellenausfall) und **fünf Merger-Tests**
(einschließlich langem Primärbeleg). Das Backend wurde danach erfolgreich neu
paketiert; die eigene Testdatenbank wurde entfernt. Keine erneute Gesamtsuite.
