# Discovery: bestätigte Reihen und verlässliche Übernahme

Auftrag vom 06.11.2026, nach Abschluss von Control Center 2.0. Feature-Branch
`fix/discovery-confirmed-series` auf `feat/control-center`; gesonderter Folge-PR.
Keine neuen Worktrees, Produktionsdaten, Merges oder Deployments.

## Anforderung und Einordnung

Speichern bleibt ohne Import. Bestätigen & übernehmen wird ein transaktionaler
Backend-Vorgang mit echter Fehlerantwort bei Konflikten. Wiederverwendbare,
ausdrücklich bestätigte Namensvarianten nutzen die bestehende Alias-Tabelle;
Special-/Datums-/Line-up-Zusätze werden nicht automatisch gelernt. Quellenfreigabe
bleibt unabhängig von Reihenfreigabe. Konzerte bleiben von Discovery ausgeschlossen;
ein bereits existierender Konzerttermin darf eine eigenständige Aftershow nicht
allein wegen desselben Tages verhindern. Sperren sind im aktuellen Kalendermodell
ganztägig; Endzeiten werden nicht erfunden.

Der neue Genreauftrag ersetzt ausdrücklich die 50-%-Definition aus Kapitel 28:
Nenner sind erkannte Eventgenres, nicht alle aktiven Projektgenres. Standardgenres
bekannter Reihen bleiben eine separat ausgewiesene Grundlage. Nicht belegte
Eventgenres werden nicht ergänzt.

## Phasen

1. Bestandsaufnahme: abgeschlossen. Import meldet Konflikte bisher durch stilles
   Return, UI speichert vorher separat; Bewertung und Import sperren pauschal
   Location/Tag. Namensmatcher und Alias-Tabelle vorhanden; neue Termine müssen
   weiter Quellen-/Identitäts-/Lifecycle-Prüfungen bestehen. Redaktion liest Events
   unmittelbar aus dem Katalog; kein automatisch angelegter Post erforderlich.
2. Bestätigung, Kalenderkonflikte und Alias-Wiederverwendung: umgesetzt.
3. Genreberechnung und Darstellung: umgesetzt.
4. Regressionen, Modulprüfungen und regulärer Commit-Hook: erfolgreich.
   Commit/Push und gesonderter Folge-PR schließen die Aufgabe ab.

## Prüfungen und Grenzen

Ausgangsstand: Control-Center-Commit `63d98ac`, erfolgreicher QUICK-Hook,
63 Frontendtests und STANDARD-Frontend. Neue Datenbanktests ausschließlich über
das Quality Gate in dessen kurzlebiger PostgreSQL-Datenbank. CLI-Prüfungen sind
kein Live-IDE-Nachweis. CI-FULL für den exakt akzeptierten Commit bleibt vor Merge
verpflichtend. Ergebnisse werden nach tatsächlicher Ausführung ergänzt.


## Umsetzung

Bestätigung ist eine gesperrte Backend-Transaktion; Wiederholung ist idempotent.
Ein Fehler bei Import oder Aliasprüfung rollt die Bestätigung zurück. Speichern
bleibt ein eigener importfreier Vorgang. Das Frontend erwartet eine echte
Kalendereintrags-ID und zeigt Konflikte bei unveränderten Eingaben. Kalender,
Partyreihen, Redaktion und offene Vorschlagsliste werden danach aktualisiert.

Bewertung und Import verwenden dieselbe Kalenderprüfung. Der CalendarEntryService
selbst enthielt keine Location/Tag-Sperre und musste nicht geändert werden.
Eindeutig getrennte Termine werden weder zusammengeführt noch als Änderungen
verknüpft. Derselbe belegte Event mit widersprüchlichen Zeiten bleibt gesperrt.
Ein während der Gesamtprüfung gefundener Fehler bei wiederverwendeten IDs ist
behoben: Ein Ersatzkandidat wird nicht an den Originaltermin gebunden. Der
bestehende explizite Cancellation-/Replacement-Ablauf bleibt erhalten.

Zusätzliche Aliase erfordern die ausdrückliche Auswahl und einen belegten
wiederverwendbaren Namen. Bekannte Namen/Aliasse erzeugen keine redundanten
Einträge. Auch Portfolioaufnahme speichert keine kompletten Editionstitel als
automatische Aliase mehr. Matcher-Version `party-rules-5` und erneute Bewertung
unveränderter automatischer Vorschläge berücksichtigen neue Katalogregeln,
ohne manuelle Entscheidungen zurückzusetzen oder unveränderte Audits zu erzeugen.

Die Genregrundlage unterscheidet SOURCE/STANDARD/MISSING. Erkennung verwendet
bestehende Katalognamen/-aliase; explizite JSON-Genres und beschriftete Genrelisten
können auch nicht zum aktiven Portfolio passende Werte belegen. Frei formulierte
unbekannte Genres werden nicht erraten. Zusammengesetzte Genrenamen und Aliasse
zählen nicht mehrfach. Standardgenres bleiben separat sichtbar, auch nach einer
lokalen Änderung der gewählten Reihe im Prüfdialog.

## Ausgeführte Prüfungen

- STANDARD-Backend erfolgreich, javac und ECJ für Haupt-/Testcode ohne blockierende
  Fehler, Warnungen oder INFO-Diagnosen. 191 Tests: 190 erfolgreich, ausschließlich
  der dokumentierte optionale historische Offline-Exporttest ohne Exportdatei
  übersprungen. Backend-Paket erfolgreich; temporäre PostgreSQL-Datenbank entfernt.
- STANDARD-Frontend erfolgreich: TypeScript, ESLint mit null Warnungen, 66 Tests
  und Produktionsbuild. Der abschließende reguläre QUICK-Hook ist ebenfalls
  erfolgreich: 67 Frontendtests einschließlich der lokal gewählten Genregrundlage,
  betroffene Backend-Unit-Tests einschließlich der Längenbegrenzung je Genre sowie
  frische Compiler-/Nullanalysen. Datenbanktests wurden im Hook ausdrücklich
  zurückgestellt und zuvor im erfolgreichen STANDARD-Backend ausgeführt.
- Drei echte lokale Chrome-Szenarien bei 1440/390/320 px und drei Themes erfolgreich:
  Konflikt ohne falsche Erfolgsmeldung, danach Übernahme, Entfernen aus Prüfbedarf,
  Kalenderdetail und tatsächlicher Redaktionseditor des importierten Termins.
  Ausschließlich synthetische Antworten, keine echten Daten-/Instagram-Aufrufe.
  Desktopkonflikt und mobiler Redaktionseditor tatsächlich gesichtet. Artefakte:
  `.quality/discovery-confirmation/`; Skript im Frontend versioniert.
- `git diff --check` erfolgreich. Frühe Nullanalyse-/Testfehler wurden behoben;
  sämtliche vorhandenen Änderungs-/Cancellation-/Discovery-Regressionen sind im
  erfolgreichen Backend-Lauf enthalten. Maven verwendete nach zwei vermeidbaren
  externen Metadaten-401-Warnungen den vorhandenen Dependency-Cache offline;
  Compiler, Tests und Paketierung liefen vollständig.

## Verbleibende Abnahmegrenzen

Kein Live-IDE- oder echter Mobilgeräte-Nachweis. Terminidentität ohne stabile
Quellenkennung bleibt bei möglichen Verschiebungen bewusst prüfpflichtig.
Endzeiten und stundenweise Sperren sind im bestehenden Modell nicht vorhanden;
Sperren gelten ganztägig. Keine automatische Freigabe oder Veröffentlichung.

Der Folge-PR war zunächst gegen `feat/control-center` (PR #6) vorgesehen.
Während des Abschlusses wurde #6 außerhalb dieses Agentenlaufs nach main gemergt
(`0ecea4b`) und sein Quellbranch gelöscht. Deshalb zielt der Discovery-PR direkt
auf main; der fachliche Diff enthält ausschließlich die Discovery-Folgeänderungen.
FULL in CI muss für den dann exakt akzeptierten Commit bestehen. Ein grüner Lauf
von #6 oder ein lokaler Modulcheck ersetzt das nicht. Kein eigenständiger Merge
und kein Deployment durch diesen Agenten.
