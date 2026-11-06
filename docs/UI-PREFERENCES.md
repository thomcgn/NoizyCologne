# Benutzerpräferenzen: Graphit und Kompakt

Auftrag vom 06.11.2026. Ausschließlich Frontend-Darstellung; keine Backend-,
Discovery-, API-, Schema- oder Deployment-Änderungen.

## Bedienung und Speicherung

Im Dashboard öffnet **Darstellung** einen tastaturbedienbaren Dialog mit zwei
unabhängigen Auswahlen:

- **Theme:** Graphit (`graphite`), Dunkel (`dark`) oder Hell (`light`).
- **Informationsdichte:** Kompakt (`compact`) oder Komfortabel (`comfortable`).

Ohne gespeicherte Auswahl gelten **Graphit und Kompakt**, auch für das
serverseitige Rendering. Bereits gespeicherte Hell-/Dunkel-Einstellungen werden
beibehalten. Der vorhandene Theme-Schnellschalter erreicht alle drei Themes.

Änderungen wirken sofort. `localStorage` verwendet den bestehenden Schlüssel
`noizy-theme` und ergänzend `noizy-density`. Andere Tabs desselben Browsers
werden über Storage-Ereignisse aktualisiert. Ungültige Werte fallen auf die
Standardauswahl zurück. Bei nicht verfügbarem/vollen Browserspeicher bleibt die
Auswahl für die laufende Sitzung nutzbar; der Dialog meldet, dass sie ein
Neuladen nicht übersteht. Keine API-Abfrage oder Mutation für Präferenzen.

Die Einstellung ist eine Browserpräferenz, kein serverseitiges Benutzerprofil.
Auf gemeinsam genutzten Browserprofilen gilt dieselbe Auswahl; es gibt keine
Synchronisierung auf andere Geräte. Abweichende gespeicherte Einstellungen
werden nach der Hydration geladen.

## CSS-Vertrag

Der Provider setzt `data-theme` und `data-density` am HTML-Element. Graphit
belegt die vorhandenen Variablen für Hintergrund, Text, Oberflächen, Ränder,
Fokus und Warnfarben mit neutralen Anthrazittönen. Die bestehenden
Tailwind-Flächen und gedämpften Texte erhalten passende Graphitfarben.

Dichtevariablen `--ui-page-padding`, `--ui-section-gap`, `--ui-card-padding`,
`--ui-card-gap`, `--ui-grid-gap`, `--ui-row-padding`, `--ui-dialog-padding`,
`--ui-control-height`, `--ui-control-padding-y`, `--ui-nav-height`,
`--ui-nav-group-gap`, `--ui-card-title-size` und `--ui-header-clearance`
steuern gemeinsame Navigation/Dialogs, Katalogabstände und Discovery-Karten.
Die Wahl verändert keine Daten, Filter, Zuordnung oder Übernahmeregel.
Für kleine und berührungsgesteuerte Viewports bleiben Navigations-/Aktionshöhen
mindestens 44 px; Präferenz-Selects haben ebenfalls 44 px Mindesthöhe.

## Arbeitsphasen und Prüfungen

1. Bestand geprüft: Projekt-/Frontend-Anweisungen, bestehender ThemeProvider,
   ThemeToggle, Dashboard-Shell, CSS und zugehörige Tests. Arbeitsbaum sauber.
   Ausgangsdiagnostik: 44 Frontendtests, TypeScript und ESLint erfolgreich.
2. Provider und Dialog erweitert; CSS-Farben und Dichte parametrisiert.
   Vorhandene Geschäftslogik und Discovery-Komponenten bleiben unverändert.
3. Regressionen für Standardwerte/SSR, sofortige Anwendung, unabhängige Auswahl,
   Speicherung/erneutes Öffnen, Altwerte, Schnellschalter, Tab-Synchronisierung,
   ungültige Werte, gesperrten Speicher und Escape/Fokusrückgabe ergänzt.
4. Lokale Browserprüfung mit synthetischen API-Antworten; Prüfskript
   `frontend/tests/visual-preferences.mjs`. Es akzeptiert nur lokale Ziele und
   scheitert bei API-Mutationen, Browserfehlern, horizontalem Überlauf,
   Textkontrast unter 4,5:1 oder zu kleinen mobilen Touchflächen. Kompakte Karten
   müssen tatsächlich kürzer sein als komfortable. Neuladen und echte
   Tab-Synchronisierung werden im Browser geprüft.

Browseraufruf gegen eine isolierte lokale Preview (wegwerfbares Session-Secret,
Backendziel `http://127.0.0.1:9`; einen laufenden Entwicklungsserver nicht beenden):

```sh
PLAYWRIGHT_MODULE=/tmp/noizy-discovery-visual/node_modules/playwright/index.mjs \
  DISCOVERY_VISUAL_SECRET="$TEAM_SESSION_SECRET" \
  node frontend/tests/visual-preferences.mjs
```

Playwright/Chrome und Preview-Vorgehen entsprechen
[DISCOVERY-REDESIGN.md](DISCOVERY-REDESIGN.md). Screenshots und Messwerte werden
lokal unter `.quality/ui-preferences/` erzeugt und gehören nicht zu den
Anwendungsdaten. Eine UI-Prüfung mit Browser-Fixtures ersetzt weder eine echte
Mobilgeräteabnahme noch FULL in CI für den exakt akzeptierten Commit.

Abschluss erfolgt nach den lokalen Prüfungen durch Commit mit regulärem
QUICK-Hook, Branch-Push und einen eigenen Pull Request von `feat/ui-preferences` nach `main`.
Der vorherige Redesign-PR #4 ist inzwischen gemergt.
Kein eigenständiger Merge oder Deployment. Live-IDE-Diagnosen nicht zugänglich.

## Lokaler Prüfnachweis

- 52 Frontendtests erfolgreich (44 bestehende und acht neue Präferenztests).
- TypeScript und ESLint mit `--max-warnings=0` erfolgreich; keine Code-Diagnosen.
- Zwölf Browserkombinationen erfolgreich: drei Themes × zwei Dichten × zwei
  Viewports (1440/390 px). Messwerte in `.quality/ui-preferences/results.json`.
  Die Graphit-Karte auf Desktop misst kompakt ca. 226 px statt ca. 271 px
  komfortabel; mobile Aktionsbuttons bleiben mindestens 44 px hoch.
- Sechs lokale Screenshots erstellt; Graphit auf Desktop/Mobil und der mobile
  Einstellungsdialog tatsächlich visuell kontrolliert. Keine Browserfehler,
  kein horizontaler Überlauf und keine API-Mutation im Prüflauf.
- `git diff --check` erfolgreich. Regulärer QUICK-Hook bleibt der Commitcheck;
  FULL in CI und PR-Abnahme bleiben vor Merge verpflichtend. Kein Zugriff auf
  die Live-IDE-Problemliste und keine echte iOS-/Android-Geräteabnahme.
