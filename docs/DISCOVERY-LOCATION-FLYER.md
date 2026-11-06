# Discovery-Location-Matching und Kalenderflyer

## Umfang und Ausgangsstand

Auftrag vom 06.11.2026 auf `fix/discovery-location-flyer`, aufbauend auf
`89ec2c5` aus dem noch offenen PR #13. Eigener Folge-PR gegen dessen Feature-Branch;
keine Änderung an main, kein neuer Worktree, Merge oder Deployment.

Die bestehende Geografie speichert bestätigte Locations und Namen/Aliasse mit
Ortsreferenzen/Adressen bereits in V18. Die Sonderquellen-Pipeline bewertete
auch solche eindeutigen Funde bisher mit deaktivierter Importfreigabe und
beendete den Ablauf bei bereits zugeordneten Vorschlägen. Es fehlen keine
Medienfelder: Kalendertermine besitzen `eventFlyer`, Partyreihen `defaultFlyer`.

## Umsetzung und Phasen

1. Bestandsaufnahme und betroffene Projekt-/Next-Anweisungen gelesen.
2. Kalenderdetails verwenden vorhandene Medien-URLs, Event-Flyer vor Dauerflyer,
   sonst Platzhalter. Proportionale, begrenzte Darstellung mit Link zur größeren
   Ansicht; Veranstaltungsnotizen nur im Editor, Sperrnotizen weiter sichtbar.
3. Eindeutig bestätigte Sonderquellen-Funde durchlaufen den vorhandenen
   Location-bezogenen Abgleich und Import. Bestehende automatische Vorschläge
   werden unter ihrer ID übernommen; manuelle Entscheidungen bleiben geschützt.
   Identitätsmapping und geografische Herkunft bleiben erhalten.
4. Ausdrückliche Quellenfreigabe über das bestehende `approved`-Feld und den
   vorhandenen Sonderquellen-Endpunkt/-Dialog. Neue Quellen bleiben standardmäßig
   gesperrt; Update ohne Freigabefeld erhält den bisherigen Wert. Keine pauschale
   Freigabe aufgrund einer Location-Zuordnung.
5. Gezielte Regressionen und statische Prüfungen erfolgreich; der Abschlusscommit
   erfolgt über den unveränderten regulären QUICK-Hook. Dessen konkrete Ergebnisse
   stehen im zugehörigen `.quality/run.*/`-Protokoll.

## Prüfungen und verbleibende Grenzen

- Gezielter Kalender-/Modal-Lauf: 40 Tests bestanden; nach der abschließenden
  Anpassung des Image-Mocks die sechs Kalendernavigation-/Flyertests erneut
  erfolgreich. Vorrang von Event-Flyer, Dauerflyer-Fallback, Platzhalter, Link zur
  Originaldatei, ausgeblendete Quellen-/Notiztexte und unveränderte gespeicherte
  Notizen werden geprüft. Bestehende Modal-/Editorregressionen bleiben erhalten.
- Sechs gezielte Quellen-Dialogtests bestanden, einschließlich standardmäßig
  deaktivierter Freigabe und ausdrücklicher Aktivierung im vorhandenen Dialog.
- Neun Geografie-Unit-Tests bestanden: bestätigte Aliasse und Ortsidentitäten,
  fehlende Belege, Namensvetter, widersprüchliche und mehrdeutige Zuordnungen.
- 26 Sonderquellen-Integrationstests bestanden, keine Fehler oder Skips. Enthalten
  sind automatische Übernahme, Wiederverwendung manueller Ortsbestätigung,
  unbekannte Location ohne Neuanlage, bestehende Vorschlags-ID, quellenübergreifende
  Dubletten, Quellenfreigabe/-entzug, Location-Sperren, Absagen/Verschiebungen und
  geschützte Ablehnungen. Kein automatischer Redaktionsentwurf/Freigabevorgang.
  Frischer isolierter PostgreSQL-17-Container, begrenzt auf 384 MB, danach entfernt;
  mindestens 9224 MiB Arbeitsspeicher verfügbar. Bestehende Datenbanken unberührt.
- Inkrementelle Java-Testkompilierung erfolgreich; frische ECJ-Analysen von Haupt-
  und Testcode ohne Fehler, Warnungen oder INFO-Diagnosen. Die anfangs fehlende
  Null-Typannotation eines Test-Sets wurde ergänzt und die Analyse wiederholt.
- Gezieltes ESLint der vier geänderten Frontenddateien geprüft. Ein ungenutzter
  Parameter im neuen Image-Mock wurde entfernt; dessen erneute Lintprüfung ist
  erfolgreich. Keine Regeln abgeschwächt. Der reguläre Hook prüft erneut alle
  geänderten Frontenddateien einschließlich TypeScript und Regressionen.
- `git diff --check` erfolgreich. Lokale Detailprotokolle:
  `.quality/calendar-visibility/location-*`. Kein zusätzlicher manueller QUICK-Lauf.
- Maven verwendet den vorhandenen Dependency-Cache offline. Der übliche
  JVM-/Mockito-Hinweis zu Class Data Sharing ist keine Compilerdiagnose.

Alle ressourcenintensiven Prozesse laufen nacheinander unter Speicherüberwachung,
mit begrenzten Java-/Node-Heaps, einem Node-Testworker und einer Abbruchgrenze
von 2 GB RSS für die Prozessgruppe beziehungsweise 4 GB verfügbarem RAM.
Datenbanktests verwenden ausschließlich einen neuen isolierten PostgreSQL-
Container ohne bestehende Volumes. Keine Produktionsbuilds oder echten Browser.
CLI-Ergebnisse sind kein Live-IDE-/visueller Nachweis; insbesondere die tatsächliche
Bilddarstellung in unterschiedlichen Browsern ist damit nicht visuell abgenommen.
FULL bleibt für den exakt akzeptierten Commit in GitHub CI erforderlich.
