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
scripts/check-quality.sh --full
```

Vor jedem Commit müssen das vollständige Quality Gate erfolgreich und die
VS-Code-Problemliste ohne projektbezogene Fehler oder Warnungen sein.
Conversion- und Null-Type-Safety-Diagnosen werden immer behoben.
Backend-Tests verwenden eine eigene kurzlebige Datenbank.
Voraussetzungen und Analysegrenzen: [Qualitätsregeln](docs/QUALITY.md).

## Auslieferung

Ein Push auf `main` startet die GitHub-Actions-Prüfungen, veröffentlicht die
Container in GHCR und löst anschließend automatisch das Serverdeployment aus.
Der tatsächliche Workflow hat kein zusätzliches manuelles Freigabe-Gate.
Arbeitsbranches können ohne diese Produktionsauslieferung gepusht werden.
Eine technische Auslieferung aktiviert kein Instagram-Publishing.

## Dokumentation

- [Team-Handbuch](docs/TEAM-HANDBUCH.md): verständliche Anleitung für den Alltag.
- [PROJECT.md](docs/PROJECT.md): fachliche und technische Referenz mit Ist-/Soll-Abgleich.
- [QUALITY.md](docs/QUALITY.md): verbindliche Prüfungen und Commit-Regeln.
