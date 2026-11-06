# Neue Partyreihe aus Discovery: Standardgenres

Folgeauftrag vom 06.11.2026, Ergänzung des offenen PR #15 auf
`feat/party-defaults-discovery-progress`. Zu Beginn sauberer Arbeitsbaum;
bestehende Standardwerte und Fortschrittsanzeige bleiben erhalten.

## Umsetzung

Die vorhandene Genreauswahl des Portfolio-Editors einschließlich Katalogladen und
Anzeige nicht mehr verfügbarer Altwerte ist in `StandardGenreSelector` gemeinsam
verwendbar. Portfolio und Discovery verwenden dieselbe Komponente. Sie lädt den
bestehenden `/api/v1/genres`-Katalog, bietet ausschließlich aktive Genres zur
Neuauswahl, Suche, Checkboxen und entfernbare Chips. Bestehende inaktive
Portfolio-Altwerte bleiben sichtbar und können ausdrücklich entfernt werden.

Discovery liefert `genreCoverage.suggestedStandardGenres`: nur ausdrücklich in
strukturierten Genre-Feldern oder beschrifteten Genre-Listen belegte, aktive
Kataloggenres beziehungsweise ihre bekannten Aliasse. Titel-/Fließtexttreffer,
Reihenstandards, unbekannte/inaktive Namen und als unsicher bezeichnete Angaben
werden nicht vorausgewählt. Diese konservativen Vorschläge ändern weder die
bisherige Genre-Abdeckungsberechnung noch deren automatische 50-%-Regel.

Der Portfolio-Request enthält die tatsächlich gewählten `standardGenres`.
PartySeriesService prüft und speichert sie weiterhin als Anzeigenamen aktiver
Kataloggenres. Die manuelle Aufnahme hängt nicht von der automatischen
Genre-Abdeckung oder einem bereits vorhandenen Veranstaltungsdatum ab. Sie legt
nur die Reihe an und ordnet sie dem Vorschlag zu. Nach Erfolg bleibt der Dialog
geöffnet; die neue Reihe ist sofort ausgewählt, auch während der Katalog noch
neu lädt. Mehrfachklicks werden vom bestehenden Aktionsschutz abgefangen.

Ein Kalenderimport benötigt weiterhin die eigene Bestätigung mit gültigem Datum
und bestätigter Location; Standort-, Änderungs-, Dubletten- und Importsperren des
bestehenden Importdienstes bleiben wirksam. Erneute Scans überschreiben diese
manuelle Zuordnung nicht und übernehmen den Termin nicht automatisch.
Katalog-/Speicherfehler erhalten Eingaben und Auswahl. Keine Migration, neue
Bibliothek oder zusätzliche Infrastruktur.

## Tatsächliche Prüfungen

- Inkrementelle javac-Kompilierung und neun GenreCoverage-Unit-Tests bestanden,
  einschließlich expliziter/strukturierter Genres, Aliasse, inaktiver Genres,
  unsicherer Angaben und fehlender Vorschläge bei bloßen Titel-/Texttreffern.
- Elf ausgewählte Frontendregressionen bestanden: gemeinsame Portfolioauswahl,
  Altwerte, Discovery-Suche/Checkboxen/Chips, aktive Vorschläge, manuelle Ergänzung,
  Aufnahme unter 50 %, offener Dialog und direkte Zuordnung, Mehrfachklickschutz,
  Katalogfehler, erhaltene Eingaben bei Speicherfehlern und kein Terminimport bei
  fehlendem Datum beziehungsweise fehlender Location. Anschließender bestätigter
  Import mit gültigen Angaben im Dialog ebenfalls erfolgreich geprüft.
- Drei gezielte Datenbanktests bestanden: genau die gewählten Standards dauerhaft
  speichern und nach erneutem Scan keinen Termin anlegen; manuelle API-Aufnahme
  bei 33 % und fehlendem Eventdatum; fehlendes Datum/fehlende Location verhindern
  Bestätigung; unbekannte Genres erzeugen weder eine Reihe noch eine Zuordnung.
  Ausschließlich neuer isolierter PostgreSQL-Container ohne bestehende Volumes,
  anschließend entfernt.
- Ein anfänglicher Frontend-Testfehler betraf den Testselektor für den vorhandenen
  Schließen-Button. Korrigierter Fehlerfall und anschließende Regressionen bestanden.
- Befunde der ersten ECJ-Analyse an den neuen Test-Bibliotheksgrenzen wurden
  mit expliziten Nullprüfungen behoben; Regeln/Suppressionen unverändert.
- Frische ECJ-Haupt-/Testanalyse nach den Korrekturen ohne Fehler, Warnungen
  oder INFO-Diagnosen. Die drei betroffenen Datenbanktests erneut bestanden;
  isolierter Container anschließend entfernt.
- Der reguläre QUICK-Commit-Hook ist die abschließende Prüfung. Ein Commit erfolgt ausschließlich nach erfolgreichem Hook; dessen
  Einzelprotokolle liegen unter `.quality/run.*/`.

Alle intensiven Schritte nacheinander mit vorheriger RAM-Prüfung und laufender
Speicherüberwachung: Abbruch unter 4 GB verfügbarem RAM beziehungsweise über 2 GB
Prozessgruppen-RSS, begrenzte Heaps, ein Node-Testworker. Bisher mindestens
7.865 MiB RAM verfügbar, keine Speichergrenze erreicht. Keine vollständige
Modulprüfung, Produktionsbuilds, echten Browser oder Live-IDE-Abnahme. FULL bleibt
für den exakt akzeptierten Commit vor Integration der GitHub-CI vorbehalten.
Kein eigener Merge, Deployment oder Schreibzugriff auf bestehende Datenbanken.
