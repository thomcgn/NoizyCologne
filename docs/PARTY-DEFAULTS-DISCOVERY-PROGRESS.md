# Partyreihen-Standardwerte und Discovery-Fortschritt

Auftrag vom 06.11.2026. Branch `feat/party-defaults-discovery-progress` auf
`3086a6f` aus dem anfangs noch offenen PR #14. Kein neuer Worktree, keine
Produktionsdatenänderung, kein eigener Merge oder Deployment. Die beiden
fachlichen Bereiche erhalten getrennte Commits.

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

## Phase B: Discovery-Fortschritt

Noch umzusetzen: tatsächliche Arbeitseinheiten am Laufbeginn bestimmen; Zahlen,
Phase, aktuelle Quelle/Location und Aktualisierungszeit während des Laufs
unabhängig persistieren. Abgeschlossene fehlerhafte Einheiten mitzählen,
unterbrochene Einheiten nicht vorzeitig zählen. COMPLETED/PARTIAL sind verarbeitet,
FAILED/INTERRUPTED behalten den echten erreichten Stand. Keine erfundenen
Prozentwerte für Altvorgänge, Reset oder reine Neubewertung. Bestehendes Polling
und persistierte API-Daten verwenden; kein WebSocket/neuer Hintergrunddienst.

## Ressourcen und Abnahmegrenzen

Prüfprozesse laufen nacheinander unter Speicherüberwachung (2 GB RSS pro
Prozessgruppe, Abbruch unter 4 GB verfügbarem RAM), begrenzten Heaps und mit einem
Node-Testworker. Datenbanktests ausschließlich mit frischem PostgreSQL ohne
bestehende Volumes, auf 384 MB begrenzt; Container anschließend entfernt.
Keine vollständigen Modulprüfungen, Produktionsbuilds oder echten Browser.
CLI-Prüfungen belegen keine Live-IDE-/visuelle Abnahme. Der reguläre QUICK-Hook
bleibt die Commitprüfung; FULL bleibt für den exakt akzeptierten Commit in
GitHub Actions vor Integration erforderlich.
