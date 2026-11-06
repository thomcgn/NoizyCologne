# Navigation und Discovery – Arbeitsnachweis

Auftrag vom 06.11.2026. Umsetzung im bestehenden Projekt; kein Worktree,
Deployment, Reset oder Zugriff auf gespeicherte Veranstaltungsdaten.

## Phasen

1. **Bestandsaufnahme abgeschlossen:** Root-/Frontend-Anweisungen,
   relevante Projekt-/Qualitätsvorgaben, bestehende Controller-/Portfolio-APIs,
   Katalogaktualisierung und Dialogtests gelesen. Arbeitsbaum anfangs sauber.
   Ausgangsdiagnostik: 39 Frontendtests, TypeScript und ESLint erfolgreich.
2. **Oberfläche umgesetzt:** Navigation gruppiert; Discovery behält
   `/dashboard/vorschlaege`. Hauptansicht mit Suchlaufstatus, Suchzeitpunkt,
   Suchbutton und sekundären technischen Aktionen. Einheitliche Karten,
   getrennte Dialoge, klare leere und fehlerhafte Zustände.
3. **Integration umgesetzt:** vorhandene Portfolio-/Import-/Rückfrage-/Quellen-
   und Reset-APIs weiterverwendet. Portfoliofreigabe erst nach ausdrücklicher
   Bestätigung im Dialog; doppelte Klicks werden abgefangen. Kalender, Portfolio
   und Redaktionsdaten werden anschließend neu geladen. Polling aktualisiert
   Übernahmehindernisse, ohne ungespeicherte Dialogeingaben zu ersetzen.
4. **Abnahme:** CLI- und Browserergebnisse siehe unten. Der erste Browserlauf
   deckte fehlende dunkle Theme-Variablen und überlappende Kopfaktionen auf;
   beide wurden korrigiert. Kein Ersatz einer Sichtprüfung durch Unit-Tests.

## Navigation und Informationshierarchie

- **Arbeitsbereich:** Übersicht, Kalender, Discovery, Redaktion.
- **Portfolio:** Partyreihen, DJs / Akteure, Locations.
- **Verwaltung:** Genres & Einstellungen.

Alle bestehenden Linkziele bleiben bestehen. Mobile Navigation wird über Menü
geöffnet, beim Navigieren geschlossen und kann mit Escape geschlossen werden.
Die aktive Route ist mit `aria-current` markiert.

**Neue Partyreihen:** Nur offene, automatisch bewertete unbekannte Reihen mit
belegtem Termin, aktivem Veranstaltungsstatus und ausreichender Genre-Abdeckung.
Karten enthalten Titel, Location, Termin, erkannte Genres, Prozent-Match,
Quellenlink und Portfolioaktion. Der Nenner bleibt die vom Backend gelieferte
Anzahl aller aktiven Projektgenres; keine eigene Matcher-Pipeline im Frontend.

**Prüfbedarf:** Offene Rückfragen, fehlende/widersprüchliche Angaben,
Kooperationen, Mehrdeutigkeiten, gesperrte Änderungen und geschützte manuelle
Entscheidungen. Verständliche Begründung auf der Karte; technische Befunde im
Dialog einklappbar. Eindeutig erkannte übernahmefähige bekannte Reihen erscheinen
unter Verlauf → Bereit zur Übernahme, ohne neue Portfoliofreigabe.
Eine ausdrücklich manuelle Portfoliofreigabe bleibt im Prüfdialog verfügbar,
wenn die bestehende API sie zulässt, auch wenn noch kein belastbarer Termin
vorliegt. Die automatische Kalenderübernahme entscheidet weiterhin das Backend.

**Verlauf:** Übernommene Termine (mit automatischem/manuellem Ursprung),
ignorierte Konzerte und weitere Ausschlüsse, Dubletten, Archiv, manuelle
Ablehnungen und Neubewertungen. Änderungen & Absagen und abgeschlossene Suchläufe
liegen darin als eigene aufklappbare Bereiche. Quellenverwaltung und technische
Diagnosen stehen separat unter Quellen & Konfiguration.

## Geänderte Frontend-Dateien

| Datei unter `frontend/` | Zweck |
| --- | --- |
| `src/app/dashboard/DashboardNavigation.tsx` | Gruppierte Desktop-/Mobilnavigation |
| `src/app/dashboard/portfolio/page.tsx` | Linkbeschriftung Discovery |
| `src/app/globals.css` | Responsive Karten, Navigation, Abstände und explizite dunkle Themefarben |
| `src/app/dashboard/vorschlaege/page.tsx` | Laden, Status, Kennzahlen, Aktionen und Zusammensetzung |
| `…/DiscoveryCandidates.tsx` | Einheitliche Karten, leere Zustände und schrittweises Anzeigen |
| `…/DiscoveryHistory.tsx` | Nachgeladenes Archiv und bestehender Änderungsworkflow |
| `…/CandidateReviewDialog.tsx` | Bestehende Freigabe, Korrektur und Rückfrage |
| `…/CandidateAssessment.tsx` | Bestehende detaillierte Bewertung |
| `…/SourceEditorDialog.tsx` | Bestehende Quellenkonfiguration |
| `…/discoveryTypes.ts` | Gemeinsame API-Typen |
| `…/discoveryPresentation.ts` | UI-Gruppierung, verständliche Gründe, Datums-/Quellenanzeige |
| `tests/discovery-assessment.test.mjs` | Angepasste und zusätzliche DOM-/Integrationsregressionen |
| `tests/fixtures/discovery.mjs` | Ausdrücklich synthetische Browserdaten |
| `tests/visual-discovery.mjs` | Lokaler Chrome-/Playwright-Test mit Screenshots |

`…/` bezeichnet `src/app/dashboard/vorschlaege/`. Backend, Schema, Crawler,
Quality-Gate und Deployment-Konfiguration wurden nicht verändert.

## Prüfungen und sichtbare Belege

Die Browserprüfung verwendet die echte Next-/React-Oberfläche und ausschließlich
abgefangene API-Antworten. Jede API-Mutation im Screenshotlauf lässt den Test
fehlschlagen. Lokales Chrome mit Playwright; keine Browserfehler und kein
horizontaler Seitenüberlauf zulässig. Zusätzlich werden Textkontraste von
Überschriften, Kennzahlen, Navigation und Aktionsbuttons geprüft (mindestens
4,5:1). Das ist eine gezielte Kontrastprüfung, kein vollständiges WCAG-Audit.

| Zustand | Beleg |
| --- | --- |
| Desktop, mehrere neue Reihen, Kooperation als Ausnahme | [Desktop](screenshots/discovery/desktop.png) |
| Keine neuen Vorschläge, helles Layout | [Leerer Zustand](screenshots/discovery/empty.png) |
| 500 historische Kandidaten als Fixture, schrittweise Anzeige | [Verlauf](screenshots/discovery/history.png) |
| Laufender Suchlauf, deaktivierter Suchbutton | [Suchlauf](screenshots/discovery/running.png) |
| Fehlerhafte Quelle, übrige Ergebnisse bleiben sichtbar | [Quellenfehler](screenshots/discovery/source-error.png) |
| Mobile Discovery, 390 px | [Mobil](screenshots/discovery/mobile.png) |
| Geöffnete mobile Navigation, helles Layout | [Mobile Navigation](screenshots/discovery/mobile-navigation.png) |
| Mobiler Freigabedialog, Escape und Fokusrückgabe | [Dialog](screenshots/discovery/mobile-dialog.png) |

Maschinenlesbare Browserergebnisse: [results.json](screenshots/discovery/results.json).

### Abschließende CLI-Ergebnisse

- **44/44 Frontendtests erfolgreich**, TypeScript und ESLint mit
  `--max-warnings=0` erfolgreich; keine Fehler oder Warnungen.
- **QUICK erfolgreich**, Protokoll `.quality/run.46cScD/`, Exitcode 0.
  Wegen der neuen Bildartefakte wählte die unveränderte Scope-Erkennung zusätzlich
  die anderen Module: 75 Gate-/Hooktests, elf Java-Analysefixtures,
  zwei Frontend-Negativtests und zehn schnelle Workerchecks erfolgreich.
  javac und die vorhandenen ECJ-Analysen melden keine Code-Diagnosen.
- Backend-Unit-Auswahl: **102 erfolgreich, ein ausdrücklich optionaler
  historischer Replay-Test übersprungen**, weil `NOIZY_DISCOVERY_SNAPSHOT` nicht
  gesetzt ist. Surefire kennzeichnet diesen bekannten Skip mit `[WARNING]`.
  Datenbankintegrationstests wurden von QUICK ausdrücklich auf STANDARD/FULL
  verschoben. Kein Datenbankcontainer und keine Entwicklungsdatenbank verwendet.
- Der Online-QUICK-Lauf enthielt HTTP-401-Metadatenwarnungen zweier externer
  Maven-Repositories sowie den JVM-CDS-Hinweis des bestehenden Mockito-Agenten.
  Die drei betroffenen Maven-Schritte wurden **offline** mit denselben lokal
  vorhandenen Abhängigkeiten und unveränderter Testauswahl erneut erfolgreich
  ausgeführt (`offline-backend-compile.log`, `offline-java-tools.log`,
  `offline-backend-tests.log` im selben Protokollverzeichnis). Für diesen
  Wiederholungslauf war ausschließlich die mit dem Agenten inkompatible
  Class-Data-Sharing-Optimierung über `JAVA_TOOL_OPTIONS=-Xshare:off` aus;
  Agent, Compilerprüfungen und Testassertions blieben aktiv. Repository- und
  CDS-Warnungen traten dabei nicht mehr auf. Keine Projektkonfiguration geändert.
- **8/8 Browserzustände erfolgreich**. Alle Screenshots tatsächlich geöffnet
  und visuell kontrolliert. Geprüfte Textkontraste mindestens 5,84:1;
  keine Browserfehler und kein horizontaler Seitenüberlauf. Mobile Navigation
  schließt bei Escape und Linkwahl; der Dialog hält den Tab-Fokus und gibt ihn
  bei Escape an seinen Auslöser zurück.
- `git diff --check` erfolgreich. **Commit und Feature-Branch-Push nachträglich vom Nutzer beauftragt; Abnahme per Pull Request. Kein Merge oder Deployment**.
  FULL und Live-IDE-Problemliste wurden nicht geprüft.

### Browserprüfung wiederholen

Einen separaten lokalen Next-Server mit einem **wegwerfbaren**
`TEAM_SESSION_SECRET` (mindestens 32 Zeichen), `LOCAL_BACKEND_URL=http://127.0.0.1:9`
und Port 3107 starten. Einen bereits laufenden Entwicklungsserver nicht beenden.
Für diese Abnahme lief ein eigener temporärer Custom-Server mit separatem
Ausgabeverzeichnis unter `.next/`; Projektkonfiguration blieb unverändert.
Nach Quelländerungen die isolierte Preview frisch starten, damit keine alten
Buildartefakte fotografiert werden.

Playwright kann außerhalb des Repos installiert werden; keine neue App-Abhängigkeit:

```sh
npm install --prefix /tmp/noizy-discovery-visual --no-package-lock playwright@1.64.1
PLAYWRIGHT_MODULE=/tmp/noizy-discovery-visual/node_modules/playwright/index.mjs \
  DISCOVERY_VISUAL_SECRET="$TEAM_SESSION_SECRET" \
  node frontend/tests/visual-discovery.mjs
```

`DISCOVERY_VISUAL_SECRET` muss dem separaten Next-Prozess entsprechen.
`CHROME_PATH`, `DISCOVERY_VISUAL_URL` und `DISCOVERY_VISUAL_OUTPUT` sind optional
konfigurierbar. Der Test akzeptiert nur localhost oder 127.0.0.1, blockiert
fremde Browserziele und ersetzt sämtliche `/api/v1/`-Antworten durch Fixtures.

## Grenzen

Die unveränderte API liefert höchstens 500 Kandidaten pro Status/Filter.
Offene Status werden getrennt geladen, damit große historische Bestände sie
nicht verdrängen. Die Listen rendern zunächst zwölf Karten bzw. zwanzig
Verlaufseinträge. Dies ist keine serverseitige Pagination; sehr große offene
Bestände können weiterhin abgeschnitten sein. Dubletten werden innerhalb des
vom bestehenden Archivfilter gelieferten Bestands ausgewählt.

Die Browserabnahme belegt synthetische Zustände in Desktop-Chrome und mobilen
Chrome-Viewports, keine reale iOS-/Android-Geräteabnahme und keinen Live-Import
gegen eine Datenbank. Die bestehende Backendfunktion wurde weder umgebaut noch
mit echten Daten ausgeführt. Kein Zugriff auf die Live-VS-Code-Problemliste;
CLI-Befunde sind davon getrennt. FULL für den exakt zu mergenden Commit bleibt
in GitHub Actions verpflichtend. Der Nutzer hat anschließend Commit und Push
auf `feat/discovery-integration` sowie Abnahme per Pull Request beauftragt.
Merge und Deployment bleiben einem gesonderten Auftrag vorbehalten.

## Übergabe per Pull Request

Am 06.11.2026 hat der Nutzer Commit und Push als dauerhaften Aufgabenabschluss
und die Abnahme sämtlicher Änderungen per Pull Request festgelegt. Diese Regel
ist in der Root-`AGENTS.md` hinterlegt. Der reguläre Pre-Commit-Hook führt QUICK
aus; kein Hook wird umgangen. Für den Hooklauf werden die bereits geprüften
lokalen Maven-Abhängigkeiten offline verwendet und die mit dem bestehenden
Mockito-Agenten inkompatible CDS-Optimierung nur im Prüfprozess deaktiviert.
Compilerregeln, Agent und Testauswahl bleiben unverändert.
