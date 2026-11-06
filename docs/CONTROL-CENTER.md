# Control Center 2.0 – Arbeitsnachweis

Auftrag vom 06.11.2026. UI-/UX-Refactoring im bestehenden Projekt. Keine neuen
Worktrees, Datenänderungen, Backend-/Schema-/Matching-Änderungen, eigenständigen
Merges oder Deployments. Ausgangsstand: `1ef7d71` (Darstellungspräferenzen).
Bestehende APIs, Authentifizierung, Redaktion und Absageabläufe bleiben erhalten.

## Phasen und Status

1. Designsystem und Desktop-Shell – umgesetzt und lokal geprüft.
2. Discovery als kompakte Arbeitsliste – umgesetzt und lokal geprüft.
3. Portfolio/Kalender, Tabellen und Detailbereiche – umgesetzt und lokal geprüft.
4. Globale Suche und Command-Palette – ausstehend.
5. Redaktion und mobile PWA – ausstehend.

Jede Phase erhält einen eigenen Commit mit regulärem QUICK-Hook und Push auf
`feat/control-center`. Der PR bleibt bis zum Abschluss aller Phasen ein Entwurf.
FULL in CI für den exakt akzeptierten Commit bleibt vor Merge verpflichtend.

## Bestandsaufnahme

Projekt-/Frontend-Anweisungen, relevante Projektabschnitte, Theme-/Dichteprovider,
Shell, Discovery, Katalogkontext, Portfolio-, Akteurs-, Kalender- und
Redaktionsoberfläche sowie bestehende Tests geprüft. Arbeitsbaum anfangs sauber.
52 Frontendtests, TypeScript und ESLint im Ausgangsstand erfolgreich.

Die bestehende Übersicht enthält veraltete Platzhalter. Das Refactoring ersetzt
sie durch tatsächliche Katalog-/Redaktionsdaten. Individuelle Rollen,
Instagram-Publishing und Push-Zustellung werden nicht als verfügbar erfunden.
Nur tatsächlich vorhandene Aktionen werden angeboten. Bestehende Route
`/dashboard/vorschlaege` sowie Portfolio-/Akteur-Aliasse bleiben erreichbar.

## Grenzen der Prüfung

Lokale CLI-/DOM- und Browserprüfungen verwenden synthetische Daten. Keine Prüfung
gegen Entwicklungs-/Produktionsdatenbanken. Live-IDE-Problemliste nicht verfügbar.
Screenshot- und Messartefakte liegen unter `.quality/control-center/`; das
Browser-Prüfskript wird mit dem Frontend versioniert. Kein vollständiges
WCAG-Audit oder echte iOS-/Android-Geräteabnahme.

## Phase 1

Zentrale Graphite-/Midnight-/Light-Tokens gemäß Auftrag, gespeicherte Dichte,
validierte Theme-Initialisierung vor dem Body, migrierte alte Dunkel-Auswahl.
Desktop-Shell nutzt die volle Arbeitsbreite, einen mitwandernden Header und eine
kompakt einklappbare Sidebar. Getrennte erreichbare Verwaltungsseiten für Genres,
Automatisierung und Einstellungen; Übersicht aus echten Katalog-/Entwurfsdaten.
Keine erfundenen Veröffentlichungs-/Push-Aktionen. Header-Hinweis nur bei
tatsächlichen Entwurfsblockern. Suche folgt in Phase 4.

Prüfungen: 53 Frontendtests, TypeScript und ESLint ohne Fehler/Warnungen. Neun
Chrome-Szenarien mit drei Themes, zwei Dichten, 1440/768/390/320 px, gefüllten,
leeren und fehlerhaften Antworten erfolgreich. Einklappen, mobile Navigation und
Escape geprüft; Screenshots unter `.quality/control-center/phase-1/` tatsächlich
geöffnet. Regulärer QUICK-Hook ist der Commitcheck.


## Phase 2

Discovery verwendet standardmäßig kompakte Tabellen mit Titel, Location, Termin,
Genres, Genre-Match, Quelle und Freigabe-/Klärungsstatus. Suche über Titel,
Location und Genres, sortierbare Spalten, Genre-Match-Filter und Seiten mit
25 Einträgen laufen auf den bereits geladenen Ergebnissen. Die Kartenansicht
bleibt optional und wird je Bereich lokal gespeichert. Die gemeinsame
`DataCollection` wird für weitere Portfolioansichten wiederverwendbar angelegt.
Filter ohne Treffer behaupten keine vollständig abgearbeitete Discovery.

Prüfen und Portfoliofreigabe öffnen einen seitlichen Dialog mit bestehender
Fokusführung und Rückkehr zum Auslöser. Escape und Schließen fragen beim
Verwerfen geänderter Eingaben nach; beim Verlassen der Seite greift der
Browserhinweis. Erfolgreiches Speichern schließt weiterhin direkt. Historie und
Quellen bleiben nachgeordnet, ihre direkten Fragmentlinks öffnen den jeweiligen
Bereich. Desktop-Suchlauf bleibt im Arbeitsbereichsheader, mobil auf der Seite.

Prüfungen: STANDARD-Frontend einschließlich TypeScript, ESLint, 55 DOM-Tests und
Produktionsbuild erfolgreich. Nach Ergänzung der gesperrten Zeilenaktion erneut
gebaut; der reguläre QUICK-Hook prüft den finalen Commitstand. Neun erfolgreiche
Chrome-Szenarien bei 1440/768/390/320 px, drei Themes, beiden Dichten sowie leeren
und fehlerhaften Antworten. Suche, Seitenwechsel, Kartenansicht, Drawer,
Tab-/Shift-Tab-Fokusgrenzen, Escape und Fragmentlinks tatsächlich geprüft.
Kein horizontaler Seitenüberlauf und keine Browser-JavaScriptfehler. Screenshots
unter `.quality/control-center/phase-2/`, Desktopliste und mobiler Light-Drawer
geöffnet und gesichtet. Ausschließlich synthetische Daten, keine API-Schreibaufrufe.
Live-IDE-/echte Mobilgeräteprüfung und CI-FULL sind damit nicht nachgewiesen.
Phasen 3–5 bleiben offen; der Aufgaben-PR bleibt ein Entwurf.


## Phase 3

Partyreihen, Locations und Akteure verwenden kompakte, sortierbare Tabellen mit
Suche und 25 Einträgen je Seite. Beziehungen, Genres, Turnus und Flyerstatus
bleiben sichtbar. Akteure filtern nach Art/Rolle, Kalendereinträge nach Location
und Party/Sperre. Kalenderliste startet chronologisch; bestätigte Absagen und
Sperren bleiben gekennzeichnet. Details und Bearbeitung öffnen seitliche Dialoge
mit Fokusführung und Abbruchschutz; erfolgreiches Speichern schließt direkt.
Der Kalendereditor scrollt auf kleinen Displays ohne verkleinerte Schrift.

Prüfungen: TypeScript und vollständiges ESLint erfolgreich; nach Korrektur zweier
Testannahmen (Rückgabe des Speicher-Mocks und bestehendes Trimmen von Notizen)
61 Frontendtests erfolgreich. Neun Chrome-Szenarien für alle vier Ansichten bei
1440/768/390/320 px, drei Themes, beiden Dichten und leeren/fehlerhaften Antworten
erfolgreich. Suche, Drawer, Fokus-Rückkehr und fehlender Seitenüberlauf geprüft;
Desktoptabelle und mobiler Light-Kalenderdialog tatsächlich gesichtet. Artefakte:
`.quality/control-center/phase-3/`. Ein erster Build kollidierte mit dem isolierten
Vorschauserver im Next-Ausgabeverzeichnis; Wiederholung nach dessen Beenden
erfolgreich. Regulärer QUICK-Hook als Commitprüfung.
Kein Zugriff auf echte Daten und keine API-Schreibaufrufe in den Browserszenarien.
Live-IDE-/Geräteprüfung und CI-FULL bleiben gesonderte Abnahmegrenzen.
