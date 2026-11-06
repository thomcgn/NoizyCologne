# Control Center 2.0 – Arbeitsnachweis

Auftrag vom 06.11.2026. UI-/UX-Refactoring im bestehenden Projekt. Keine neuen
Worktrees, Datenänderungen, Backend-/Schema-/Matching-Änderungen, eigenständigen
Merges oder Deployments. Ausgangsstand: `1ef7d71` (Darstellungspräferenzen).
Bestehende APIs, Authentifizierung, Redaktion und Absageabläufe bleiben erhalten.

## Phasen und Status

1. Designsystem und Desktop-Shell – umgesetzt und lokal geprüft.
2. Discovery als kompakte Arbeitsliste – ausstehend.
3. Portfolio/Kalender, Tabellen und Detailbereiche – ausstehend.
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
