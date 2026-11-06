# Control Center 2.0 – Arbeitsnachweis

Auftrag vom 06.11.2026. UI-/UX-Refactoring im bestehenden Projekt. Keine neuen
Worktrees, Datenänderungen, Backend-/Schema-/Matching-Änderungen, eigenständigen
Merges oder Deployments. Ausgangsstand: `1ef7d71` (Darstellungspräferenzen).
Bestehende APIs, Authentifizierung, Redaktion und Absageabläufe bleiben erhalten.

## Phasen und Status

1. Designsystem und Desktop-Shell – umgesetzt und lokal geprüft.
2. Discovery als kompakte Arbeitsliste – umgesetzt und lokal geprüft.
3. Portfolio/Kalender, Tabellen und Detailbereiche – umgesetzt und lokal geprüft.
4. Globale Suche und Command-Palette – umgesetzt und lokal geprüft.
5. Redaktion und mobile PWA – umgesetzt und lokal geprüft.

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


## Phase 4

Globale Suche im Header, erreichbar per Strg/⌘ K. Sie durchsucht die tatsächlich
im Katalog geladenen Partyreihen, Locations, Akteure und Termine und bietet
Navigation sowie die aktuelle Arbeitsbereichsaktion an. Maximal fünf Treffer je
Bereich, Lade-/Fehlerzustände und ein leerer Suchzustand sind sichtbar. Pfeiltasten,
Tab und Escape funktionieren mit Fokus-Rückkehr. Ein geöffneter Editor wird durch
den Shortcut nicht mit einem zweiten Dialog überlagert. Datensatzlinks öffnen den
passenden Drawer auch nach verzögertem Laden und bei Hash-Navigation auf derselben
Seite. Keine neuen Such-APIs und keine Datenänderungen durch Suche.

Prüfungen: STANDARD-Frontend mit TypeScript, ESLint (null Warnungen), 63 Tests und
Produktionsbuild erfolgreich. Neun Chrome-Szenarien mit drei Themes, zwei Dichten,
1440/768/390/320 px und leeren/fehlerhaften Daten erfolgreich. Fokus, Pfeiltasten,
Shortcut, Escape sowie tatsächlicher Wechsel vom Treffer zum passenden
Akteurseditor geprüft. Screenshot der Desktop-Suche gesichtet; Artefakte unter
`.quality/control-center/phase-4/`. Regulärer QUICK-Hook prüft den Commitstand.
Bestehende Live-IDE-/Geräte-/CI-Grenzen bleiben bestehen.


## Phase 5

Desktop-Redaktion zeigt Feed-Anordnung, Post-Vorschau und Editor ab 1400 px
nebeneinander; mittlere Breiten verwenden zwei Spalten. Der Feed scrollt in
begrenzter Höhe, Vorschau und Planung bleiben am Desktop erreichbar. Unter
1024 px wechseln Planung/Vorschau/Bearbeiten ohne Verlust der lokalen Entwürfe.
Datensatzlinks wählen den passenden Termin. Der Header bietet die vorhandene
Speicheraktion; Status und ungespeicherte Eingaben sind sichtbar. Verlassen über
Links und Browser-Neuladen schützen ungespeicherte Entwürfe. Manuelle Reihenfolge,
Flyer, Musik, Caption, Mentions und bestehende Freigabesperren bleiben erhalten.
Der mobile Ausbau betrifft die vorhandene Weboberfläche; Offline-Synchronisation,
Push und native Gerätefunktionen werden dadurch nicht als verfügbar behauptet.

Prüfungen: STANDARD-Frontend mit TypeScript, ESLint, 63 Tests und Produktionsbuild
erfolgreich. DOM-Regression prüft Caption-Erhalt, mobile Bereichswechsel,
Navigations-/Neuladeschutz und erfolgreiches Speichern. Neun Chrome-Szenarien bei
1440/768/390/320 px, drei Themes, beiden Dichten und leeren/fehlerhaften Daten
erfolgreich; direkte Terminwahl, editierte Caption mit Live-Vorschau und mobile
Bereichswechsel geprüft. Ein expliziter zugänglicher Name für die Caption behebt
die im Browser gefundene Label-Mehrdeutigkeit; der abschließende QUICK-Hook prüft
diesen letzten Stand erneut. Desktop und mobile Light-Vorschau gesichtet,
Artefakte unter `.quality/control-center/phase-5/`. Keine echten Daten-/Instagram-
Schreibvorgänge. Live-IDE-/Geräteabnahme und verpflichtendes CI-FULL bleiben offen.
Alle fünf Implementierungsphasen sind abgeschlossen; fachliche PR-Abnahme bleibt
beim Nutzer. Der nachfolgende Discovery-Fachauftrag wird getrennt bearbeitet.


## Touch-Hover-Korrektur (06.11.2026)

Ausschließlich `.cc-table tr:hover td` aus der gemeinsamen Hintergrundregel
entfernt, damit Touch-Geräte keinen bleibenden Zell-Hover in Veranstaltungskarten
zeigen. `tr[aria-selected="true"]` und sämtliche Fokusregeln unverändert.
Gezielter Vorher-/Nachher-Vergleich und `git diff --check` bestanden; der reguläre
QUICK-Hook ist die Commitprüfung. Keine Layoutänderung, kein lokaler Build oder
Browser-/Gerätetest. FULL bleibt vor Integration der GitHub-CI vorbehalten.
