# Performance und Multi-Source-Discovery – Arbeitsnachweis

Basis: `b985e68`, bereits geprüfte JavaScript-Discovery plus Quality-Strategie.
Feature-Branch `feat/multi-source-discovery`; keine Produktionsdatenänderung,
kein Merge nach main, kein Deployment. Bestehende lokale Upload-Änderungen bleiben
im ursprünglichen Worktree erhalten. Die Umsetzung nutzt vorhandene Dienste und
Tabellen statt zusätzlicher Infrastruktur.

## Phase 1 – Entwicklungsworkflow

Der Ausgangsstand besitzt bereits QUICK-Hook, Feature-QUICK, PR-/main-FULL,
SHA-gebundene Freigaben vor GHCR/Deployment, Smoke-Test/Rollback und Dependency- /
BuildKit-Caches. Diese geprüften Funktionen bleiben erhalten. Ein vorheriger
vollständiger Lauf für `b985e68` dauerte rund vier Minuten; wiederholte manuelle
QUICK-/FULL-Läufe unmittelbar vor dem Hook verursachten vermeidbare Mehrarbeit.
Weitere Kosten: Java-Negativtests ca. 50 Sekunden und Gate-Selbsttests ca. 40–50
Sekunden bei Werkzeugänderungen, Datenbankstart ca. 15 Sekunden plus Integration.

Änderungen: QUICK kompiliert/analysiert alle aktuellen Java-Testquellen, führt aber
nur ausgewählte Unit-Tests aus und nennt zurückgestellte Datenbanktests ausdrücklich.
Keine vollständige Docker-Datenbankumgebung im Commit-Hook. STANDARD-Backend nutzt
sichere inkrementelle Kompilierung; FULL bleibt sauber und vollständig. Löschungen
und gemeinsame Compilerinputs erzwingen weiterhin frische Ausgaben. Agent-Regeln
fordern gezielte Wiederholungen, kleine Aufgaben und das Vermeiden doppelter
unveränderter Komplettprüfungen. Es gibt keine Ergebnis-Zertifizierung aus alten
Caches oder geänderte Warnungsregeln.

Prüfungen: Scope-/Workflow-Fixtures prüfen Unit-Auswahl, ausdrücklich zurückgestellte
Integration, STANDARD mit eigener Testdatenbank, Fehler-/Cleanup-Propagation,
Indexschutz und unveränderte FULL-Pflicht. Aktuelle Laufzeiten liegen pro Lauf in
`.quality/run.*/timings.json`; QUICK und FULL haben ausdrücklich unterschiedlichen
Prüfumfang. Ein messbarer Zeitvergleich folgt mit dem tatsächlichen Abschlusslauf.

GitHub wurde lesend geprüft: main meldet `protected: false`, Repository-Rulesets
sind leer (06.11.2026). Damit ist die CI-Freigabe vor dem Deployment technisch
vorhanden, aber ein ungeprüfter Merge noch nicht zuverlässig ausgeschlossen.
Die bestehende Anleitung und Sollkonfiguration in `docs/QUALITY.md` und
`.github/branch-protection-main.json` müssen durch einen Repository-Administrator
aktiviert werden; keine Einstellungen wurden ungefragt verändert.

Grenze: QUICK ersetzt Integrationstests nicht. Umfangreiche Backendänderungen
benötigen STANDARD, PR/Integration/Freigabe benötigt FULL für den akzeptierten
Commit. IDE-Zugriff und erfolgreiche CLI-Diagnosen sind getrennt auszuweisen.

## Phase 2 – einheitliches Quellenmodell

V16 ergänzt die bestehende Quellentabelle um Art (Website, offizieller Veranstalter,
offizielle Tickets, Plattform, sonstige Quelle), Aktivierung, Priorität, fachliche
Abrufqualität, Eventzahl und Fallbackkennzeichnung. Bestehende URLs, Profile und
Freigaben bleiben erhalten. Ungeprüfte Altquellen werden nicht nachträglich als
erfolgreich ausgegeben. Die Location erhält einen getrennten letzten Versuch und
Qualität/Zähler. Keine neue Infrastruktur oder konkurrierende Quellenverwaltung.

`SourceQualityTests` prüfen die fünf Qualitätsklassen mit statischer vollständiger
Liste, bestätigtem Leerbestand, unbekanntem HTML sowie einer MTC-artigen
unvollständigen Browserantwort. Quellenkonfiguration wird unter demselben
Transaktionslock wie Import/Prüfentscheidungen gespeichert. Vorhandene API-Aufrufe
ohne neue Felder bleiben kompatibel. Quellenherkunft ist aus dem bestehenden
Beobachtungsprotokoll abrufbar; Migration und Persistenz werden mit dem nachfolgenden
Integrationstest in einer eigenen Testdatenbank geprüft, nicht in QUICK behauptet.
