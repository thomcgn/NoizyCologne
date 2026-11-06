# NoizyCologne

Team-Anwendung für die Planung und Redaktion von Kölner Partys.
Konzerte gehören nicht zum Projektumfang.

## Für das Team

Das [Team-Handbuch](docs/TEAM-HANDBUCH.md) erklärt die tägliche Arbeit ohne
technische Vorkenntnisse: Partys und Locations pflegen, Termine eintragen,
Flyer prüfen, Texte vorbereiten und mit Änderungen oder Absagen umgehen.

Stammdaten, Kalender, Flyer und Redaktionsentwürfe werden gespeichert.
Die Website-Suche liefert Vorschläge; das Team prüft unklare Angaben.
Instagram-Veröffentlichungen und Direktnachrichten werden von der Anwendung
derzeit nicht versendet. „Freigegeben“ ist ein gespeicherter Redaktionsstatus.

## Projektstruktur

| Ordner | Inhalt |
|---|---|
| `backend/` | Spring Boot, PostgreSQL/Flyway, Fachlogik und lokale Flyer-Erkennung |
| `frontend/` | Next.js/TypeScript, Kalender und geschützte Team-Oberfläche |
| `deploy/` | Docker, Caddy und Serverkonfiguration |
| `docs/` | Team-Handbuch, Projektspezifikation und Qualitätsregeln |
| `.github/` | Prüfungen, Container-Builds und automatische Auslieferung |

## Entwicklung und Qualität

Java 21 als Build-Ziel, Spring Boot 4, PostgreSQL 17, Next.js 16 und TypeScript.
Lokal wird auch mit JDK 25 geprüft; CI verwendet Java 21 und Node 24.
Für die vollständigen Prüfungen werden außerdem Python 3 und Docker benötigt.

Frontend-Abhängigkeiten einmalig installieren und Repository-Hook aktivieren:

```sh
cd frontend
npm ci
cd ..
scripts/install-hooks.sh
scripts/check-quality.sh --quick
```

Während der Entwicklung und vor lokalen Commits laufen gezielte Prüfungen und
QUICK; der Commit-Hook führt QUICK aus. `--backend` und `--frontend` prüfen eine
ganze Komponente, `--full` beide einschließlich Produktionsbuilds. Fehler,
Conversion- und Null-Type-Safety-Diagnosen bleiben blockierend. Codex Cloud nutzt
reproduzierbare CLI-Prüfungen und benennt fehlenden Zugriff auf Live-IDE-Diagnosen.
Backend-Integrationstests verwenden eine eigene kurzlebige Datenbank.
Voraussetzungen, erforderlicher Branch-Schutz und Analysegrenzen:
[Qualitätsregeln](docs/QUALITY.md), [Laufzeitvergleich](docs/QUALITY-BENCHMARK.md).

## Auslieferung

Feature-Pushes führen QUICK aus; Pull Requests nach `main` und main-Pushes FULL.
GHCR und Serverdeployment starten automatisch erst nach erfolgreicher vollständiger
CI-Prüfung der exakten main-SHA. Ein manueller Aufruf benötigt denselben Nachweis
oder führt FULL selbst aus. Smoke-Tests und Rollback bleiben erhalten.
Der erforderliche Check **Full quality gate** muss zusätzlich durch
[Branch Protection](docs/QUALITY.md#erforderlicher-schutz-für-main) erzwungen werden;
die vorbereitete Konfiguration ist noch nicht auf GitHub aktiviert.
Arbeitsbranches können ohne automatische Produktionsauslieferung gepusht werden.
Eine technische Auslieferung aktiviert kein Instagram-Publishing.

## Dokumentation

- [Team-Handbuch](docs/TEAM-HANDBUCH.md): verständliche Anleitung für den Alltag.
- [PROJECT.md](docs/PROJECT.md): fachliche und technische Referenz mit Ist-/Soll-Abgleich.
- [QUALITY.md](docs/QUALITY.md): verbindliche Prüfungen und Commit-Regeln.

Der optionale JavaScript-Fallback der Location-Discovery und die automatische
Website-Prüfung sind in [docs/BROWSER-CRAWLER.md](docs/BROWSER-CRAWLER.md)
beschrieben, einschließlich Konfiguration, Sicherheitsgrenzen und isolierter Tests.
