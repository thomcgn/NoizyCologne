# Discovery: Sonderquellen und sofortige Team-Ablehnung

Auftrag vom 06.11.2026. Gemeint ist `https://qrushapp.de/`, eine externe
Veranstaltungsquelle und keine Location. Der bestehende Aufgaben-PR #8 wird um
die ausdrücklich beauftragte Implementierung erweitert. Keine neuen Worktrees,
Produktionsdatenänderungen, eigenständigen Merges oder Deployments.

## Phasen

1. Bestand und Anweisungen geprüft: Quellen und Kandidaten verlangen bislang eine
   Location; manuelle Ablehnung existiert im Editor, aber nicht direkt in der Liste.
2. Datenmodell/API (Migration V17), Sonderquellen-Scan und ausdrückliche Ortszuordnung: umgesetzt.
3. Sonderquellen hinzufügen/löschen und Sofort-Ablehnung in Listen und Karten: umgesetzt.
4. Regressionen und Browserprüfungen bestanden. Abnahmepaket dokumentiert; Abschluss über regulären QUICK-Hook, Aufgaben-Branch `docs/qrush-special-source` und PR #8.

## Fachliche Grenzen

Keine künstliche QRUSH-/MTC-Location. Orte müssen aus Eventangaben stammen und
zu einer bekannten Location passen oder ausdrücklich redaktionell zugeordnet
werden. Ohne ausdrückliche Ortszuordnung kein Kalenderimport. Sonderquellen erhalten keine automatische Quellenfreigabe.
Löschen entfernt die Quelle aus Konfiguration und weiteren Scans, erhält aber
bereits gefundene Termine und manuelle Entscheidungen. „Sofort ablehnen“ ist eine
protokollierte manuelle Entscheidung zu genau diesem Fund, ohne Import, neue
Genre-/Location-Regel oder pauschale Ablehnung künftiger Events einer Quelle.

## Prüfungen

- Frontend-STANDARD (`scripts/check-quality.sh --frontend`): bestanden;
  TypeScript, ESLint, 72 Tests und Next.js-Produktionsbuild erfolgreich.
- Backend-STANDARD (`scripts/check-quality.sh --backend`): bestanden; 206 Tests, 205 erfolgreich,
  ein optionaler privater Snapshot-Replay ohne bereitgestellten Export ausgelassen.
  javac und ECJ einschließlich Null-Safety ohne Diagnosen; Maven-Paket erfolgreich.
- Reproduzierbare synthetische Chromium-Prüfung in
  `frontend/tests/visual-special-sources.mjs`: 1440/390/320 px erfolgreich.
  Anlegen/Löschen ohne Location, ausdrückliche Ortswahl, Fehler und Wiederholung
  der Ablehnung; keine horizontalen Überläufe oder JavaScript-Seitenfehler.
  Screenshots in `.quality/special-sources/` geprüft.
- Nur lesender öffentlicher QRUSH-Smoke-Abruf mit dem tatsächlichen Crawler:
  acht Seiten, sieben rohe Event-Extraktionen; ein Redirect erforderte Prüfung.
  Das ist eine Teilauswertung, keine vollständige Portal-/Köln-Abdeckung.
  Kein Datenbankzugriff und keine Übernahme dieser Live-Funde.

Die Regressionen prüfen eigenständige Quellenidentität, URL-/API-Validierung,
robots.txt und Crawlgrenzen, fehlenden Ort, ausdrückliche Kalenderzuordnung,
Dubletten-Konflikt mit Rollback, widersprüchliche Portaltermine, geschützte
Teamentscheidungen bei neuen Lifecycle-Hinweisen sowie Löschen/Wiederanlegen.
Ein anfänglicher ECJ-Null-Safety-Befund in Test-Assertions und ein fehlendes
Pflichtfeld in einer neuen Testfixture wurden korrigiert. Ein Zwischenlauf mit
fehlenden Java-Symbolen scheiterte; der anschließende saubere STANDARD-Lauf
bestand ohne Compilerdiagnosen. Keine Compiler-/Warnregeln wurden abgeschwächt.

Datenbanktests laufen ausschließlich in der vom Quality Gate erzeugten
kurzlebigen PostgreSQL-Datenbank. Der reguläre QUICK-Hook bleibt das lokale Commit-Gate. QUICK verschiebt die Datenbanktests ausdrücklich auf
STANDARD/FULL; STANDARD wurde hierfür separat ausgeführt.
CLI-Prüfungen und synthetischer Chromium ersetzen keine Live-IDE-/Geräteabnahme.
FULL in CI bleibt vor Merge für den exakt akzeptierten Commit erforderlich.
