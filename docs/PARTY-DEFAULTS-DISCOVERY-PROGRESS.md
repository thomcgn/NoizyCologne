# Partyreihen-Standardwerte und Discovery-Fortschritt

Auftrag vom 06.11.2026. Branch `feat/party-defaults-discovery-progress` auf
`3086a6f` aus dem anfangs noch offenen PR #14. Kein neuer Worktree, keine
Produktionsdatenänderung, kein eigener Merge oder Deployment. Die beiden
fachlichen Bereiche erhalten getrennte Commits. PR #14 ist inzwischen außerhalb
dieses Auftrags gemergt; der neue PR richtet sich gegen main.

## Phase A: Standardwerte

Implementiert: nullable LocalTime/Integer in PartySeries, validierte API-Werte,
HH:mm-Ausgabe, V20 ohne Backfill, Pflichtzeit für neue Reihen einschließlich
Discovery-Portfolioaufnahme. Ein Slider von 0–30 € mit Schrittweite 1 und separater
Unbekannt-Option trennt NULL ausdrücklich von Eintritt frei.

Kalendervorgaben werden ausschließlich bei der Erstellung übernommen. Event-
Zeit und -Preis haben Vorrang; relative Beginnangaben bleiben ohne genaue
Standard-Uhrzeit. Ein neuer Import liest Angebote in EUR und explizit beschriftete
Eintrittspreise aus den vorhandenen Eventbelegen; er lädt keine neue Quelle.
Bestandskalender und manuelle Änderungen bleiben unverändert. Der Editor schlägt
Vorgaben nur für neue Termine vor und erhält manuelle Eingaben.

Bisherige gezielte Prüfungen:

- Inkrementelle Kompilierung und EventAdmission-Unit-Test erfolgreich.
- Vier Kalender-API-Tests sowie je eine betroffene Discovery-Portfolio- und
  Redaktionsregression erfolgreich in einer neuen isolierten Testdatenbank.
- Preisvalidierung fand zunächst Jacksons automatische Kürzung von 8,5 auf 8 €.
  Ein Deserialisierer ausschließlich für das neue Euro-Feld lehnt nicht ganzzahlige
  JSON-Werte ab. Danach alle sieben Partyreihen-API-Tests erfolgreich, inklusive
  0/NULL, Speichern/Ändern, Pflichtzeit und ungültigen Preisen/Uhrzeiten.
- Fünf gezielte Frontendtests bestanden: Standardwerte beim Anlegen/Bearbeiten,
  unbekannt/frei/30 €, Kalenderdefaults, manuelle Werte, relativer Beginn und
  Discovery-Portfolioaufnahme mit erforderlicher Zeit.
- TypeScript erfolgreich. Zwischenzeitliche Compiler-/Nullanalysebefunde wurden
  korrigiert; frische Analyse und regulärer QUICK-Hook schließen Phase A ab.
- Zusätzlicher Discovery-Importtest bestanden: verifizierte Eventwerte vor
  Reihenstandards, manuelle Kalenderkorrekturen bei erneutem Scan geschützt und
  relativer Beginn ohne genaue Uhrzeit. V20 separat gegen einen vorhandenen
  synthetischen Datensatz geprüft: Altwerte bleiben NULL, ungültige Preise und
  Sekunden werden abgelehnt. Isolierter Container entfernt.
- Der erste QUICK-Versuch stoppte korrekt wegen drei Null-Sicherheitswarnungen
  im neuen Importtest; explizite Nullprüfung und typisierter Zeitvergleich beheben sie.
- `git diff --check` erfolgreich. Der reguläre QUICK-Hook ist die abschließende
  Commitprüfung; seine Details stehen im zugehörigen `.quality/run.*/`-Protokoll.

Phase A ist als `d879dc4` abgeschlossen. Regulärer QUICK-Hook erfolgreich:
javac, frische ECJ-Haupt-/Testanalyse, Next-Typegen, TypeScript, ESLint sowie
86 Frontendtests; 121 Backendtests ohne Fehler, davon ausschließlich der
optionale private Offline-Replay mangels Snapshot übersprungen. Der abschließende
JDBC-Zeitvergleich wurde zusätzlich isoliert erfolgreich nachgeprüft und der
zugehörige reguläre QUICK-Hook ebenfalls bestanden.

## Phase B: Discovery-Fortschritt

Implementiert: tatsächliche Arbeitseinheiten am Laufbeginn bestimmen; Zahlen,
Phase, aktuelle Quelle/Location und Aktualisierungszeit während des Laufs in
REQUIRES_NEW-Transaktionen persistieren. V21 ergänzt nullable Felder ohne Backfill
und prüft 0 ≤ fehlgeschlagen ≤ abgeschlossen ≤ geplant. Abgeschlossene fehlerhafte
Einheiten zählen mit; unterbrochene Einheiten werden nicht vorzeitig gezählt.
COMPLETED/PARTIAL sind verarbeitet, FAILED/INTERRUPTED behalten den echten Stand.
Keine erfundenen Prozentwerte für Altvorgänge, Reset oder Neubewertung. Bestehendes
Polling und persistierte API-Daten versorgen die beschriftete Progressbar samt
Prozent, Einheiten, aktueller Quelle/Location und Aktualisierungszeit. Kein neuer
Hintergrunddienst/WebSocket.

Tatsächlich durchgeführte Prüfungen für Phase B:

- Inkrementelle javac-Kompilierung und zwei Prozent-/Status-Unit-Tests bestanden.
  Ein zunächst beanstandeter überflüssiger Cast wurde entfernt.
- Frische ECJ-Haupt-/Testanalyse ohne Fehler, Warnungen oder INFO-Diagnosen.
- V21 auf synthetischen Altdaten: unbekannte Zähler bleiben NULL; ungültige
  Abschluss-/Fehlerzahlen werden abgelehnt.
- Fünf neue Datenbanktests: während eines blockierten Abrufs unabhängig lesbare
  33 % mit Fehlerzählung und wiederholtem API-Abruf, danach PARTIAL mit 100 %;
  Abbruch bei 33 %; Erhalt eines verwaisten Zwischenstands; leerer Lauf;
  Monitoring nur relevanter aktiver Locations; Neubewertung ohne Scan-Prozente.
- Zwei bestehende Discovery-Importregressionen nach Scheduleränderung bestanden,
  einschließlich Partyreihen-Defaults und Schutz manueller Kalenderkorrekturen.
  Alle sieben Tests in einer neuen isolierten Datenbank; anschließend entfernt.
- Zwei gezielte Frontendtests bestanden: Polling erhöht 33 auf 67 %, erneutes
  Einhängen der Seite liest 67 % aus dem Vorgang, PARTIAL zeigt 100 % mit Fehler;
  FAILED/INTERRUPTED bleiben bei 33 %, Leerzustand und Reset bleiben unterscheidbar.
  ARIA-Werte und Aktualisierungszeit geprüft.
- Mindestens 7.819 MiB RAM während der bisherigen Phase-B-Prüfungen verfügbar;
  keine Speichergrenze erreicht und kein automatischer Neustart.
- Die Freigabe des zweiten Commits erfolgt ausschließlich über den regulären
  QUICK-Hook, einschließlich TypeScript/ESLint und bestehender Regressionen.
  Sein tatsächliches Ergebnis steht im Commitprotokoll unter `.quality/run.*/`.

## Ressourcen und Abnahmegrenzen

Prüfprozesse laufen nacheinander unter Speicherüberwachung (2 GB RSS pro
Prozessgruppe, Abbruch unter 4 GB verfügbarem RAM), begrenzten Heaps und mit einem
Node-Testworker. Datenbanktests ausschließlich mit frischem PostgreSQL ohne
bestehende Volumes, auf 384 MB begrenzt; Container anschließend entfernt.
Keine vollständigen Modulprüfungen, Produktionsbuilds oder echten Browser.
CLI-Prüfungen belegen keine Live-IDE-/visuelle Abnahme. Der reguläre QUICK-Hook
bleibt die Commitprüfung; FULL bleibt für den exakt akzeptierten Commit in
GitHub Actions vor Integration erforderlich.

## Nachkorrektur: erkannte Discovery-Genres vorauswählen (07.11.2026)

Bestandsaufnahme: Der Aufnahmedialog berücksichtigte ausschließlich
`suggestedStandardGenres` des beim Öffnen gespeicherten Kandidaten. Die bereits
vorhandenen `sourceNames` fehlten. Die Vorauswahl verwendet jetzt beide Listen
von `currentCandidate` (ersatzweise `candidate`) und ausschließlich eindeutige
Anzeigenamen des aktiven Katalogs. Sie erscheint auch nach verzögerter API-Antwort.
Manuelle Auswahländerungen einschließlich vollständiger Abwahl bleiben beim
Polling erhalten; gespeichert wird exakt diese editierbare Auswahl. Der Hinweis
nennt die Anzahl vorausgewählter erkannter Kataloggenres. Matching-Regel und
Kalenderbedingungen bleiben unverändert.

Gezielte Prüfung: fünf Frontendtests erfolgreich, davon drei neue Regressionen
für die vier Sonic-Boom-Genres mit verzögertem Katalogladen, aktuelle
Kandidatendaten/vereinigte Vorschläge/manuelle Abwahl sowie mehrdeutige,
unbekannte und inaktive Namen. Bestehende Aufnahme-/Speicher- und Unter-50-%-Fälle
bestanden ebenfalls. Ein anfänglicher Testfehler zur alphabetischen
Katalogreihenfolge und ein Testdaten-Duplikat wurden korrigiert; abschließend
keine Warnungen. Mindestens 7.454 MiB RAM verfügbar, kein Speicherabbruch.
`git diff --check` erfolgreich. Abschlussprüfung über den regulären QUICK-Hook;
FULL bleibt GitHub Actions vorbehalten. Keine lokalen Builds oder Browserläufe.

## Nachkorrektur: Stammname und wechselndes Line-up trennen (07.11.2026)

Bestandsaufnahme: Katalognamen und bestätigte Aliase wurden bereits innerhalb
längerer Eventtitel erkannt; gepflegte Standardgenres wurden wiederverwendet.
Der Aufnahmedialog schlug dagegen den vollständigen Eventtitel als Reihennamen
vor. Die API liefert jetzt einen editierbaren `suggestedSeriesName`. Eine
abgesetzte, großgeschriebene Line-up-Angabe nach „mit“, „with“ oder „feat.“ wird
bei gemischt geschriebenem Stammnamen nur für diesen Vorschlag abgetrennt.
Uneindeutige Titel bleiben vollständig als editierbarer Vorschlag erhalten;
keine pauschale Entfernung von „mit“, keine automatische Änderung bestehender
Namen oder Aliase. Der Dialog erklärt die manuelle Prüfung. Namensänderungen im
Formular bleiben beim Polling erhalten; die bestätigte Eingabe wird gespeichert.

Originaltitel und Rohdaten bleiben unverändert. Neue Kalenderübernahmen erhalten
den vollständigen Titel zusätzlich in ihren Notizen. Bei zwei offenen Vorschlägen
mit wechselndem Line-up verwendet die zweite Portfolioaufnahme eine inzwischen
angelegte passende Reihe und ordnet sie tatsächlich zu; gepflegte Standardgenres
werden nicht überschrieben. Eine abweichende manuelle Zuordnung bleibt geschützt.
Keine Änderung der Eventidentität, Termintrennung oder Importbedingungen.

Gezielte Prüfungen des Erweiterungsstands:

- javac-Haupt-/Testkompilierung erfolgreich; sieben DiscoveryAssessment-Unit-Tests
  bestanden, einschließlich aller vier Beispiele, Stamm-/Aliaserkennung und
  Standardgenres sowie unverändertem „Tanzen mit Freunden“/„Tanz mit MIR“.
- Drei Integrationstests gegen eine frische isolierte PostgreSQL-Datenbank
  bestanden: wechselnde Line-ups/eine Reihe/getrennte Termine/Originaltitel/
  Standardgenres/Alias/geschützte manuelle Zuordnung sowie bestehende
  Portfolioaufnahme und Quellenkonflikt mit geschützter Ablehnung. Container
  anschließend entfernt.
- Elf gezielte Frontendtests bestanden: die vier editierbaren Namensvorschläge,
  unveränderter uneindeutiger Titel, manuelle Eingabe über Polling bis zum
  Speichern, aktive Eventgenres für neue und Standardgenres für bekannte Reihen.
- Intensive Schritte seriell mit Speicherwächter; mindestens 6.775 MiB RAM
  verfügbar, kein Speicherabbruch. `git diff --check` erfolgreich. Abschluss
  über regulären QUICK-Hook, FULL ausschließlich in GitHub Actions. Keine
  lokalen Produktionsbuilds, Browserprüfungen oder Datenmigrationen.

Ein erster QUICK-Lauf stoppte vor dem Commit wegen einer Nulltyp-Warnung im
neuen Integrationstest. Der Katalogwert wird jetzt ausdrücklich auf NULL geprüft;
die fachlichen Datenbankergebnisse bleiben dadurch unverändert gültig.

QUICK für `7819e7b` erfolgreich: javac/ECJ ohne Projektdiagnosen, 131 Backendtests
bestanden und ein optionaler privater Replay mangels Snapshot übersprungen;
TypeScript/ESLint ohne Diagnosen, 107 Frontendtests bestanden. Während der Arbeit
wurden PR #21 und #22 extern gemergt. Der aktuelle Main-Stand wurde deshalb in
den Feature-Branch übernommen; Ausschlusshinweis und Namensvorschlag sowie beide
Ergänzungen des Kandidatentyps bleiben gemeinsam erhalten. Der Vergleich mit
Main enthält weiterhin ausschließlich die zehn Dateien dieser Erweiterung.
Die drei isolierten Datenbanktests und elf gezielten Frontendtests wurden nach
dieser Synchronisierung erneut erfolgreich ausgeführt; Testcontainer entfernt,
keine Speichergrenze erreicht. Die unveränderten Unit-Ergebnisse bleiben gültig;
der reguläre Hook prüft den zusammengeführten Stand vor dem Synchronisierungscommit.
Für die Erweiterung ist ein Folge-PR erforderlich, da #22 bereits gemergt ist.
