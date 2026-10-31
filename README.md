# NoizyCologne

Redaktions- und Automatisierungsplattform für
Musikveranstaltungen in Köln.

## Tech Stack

- Java 25 (LTS) / Spring Boot 4
- PostgreSQL / Flyway
- Next.js 16 / TypeScript
- Docker / Docker Compose
- Caddy
- GitHub Actions / GHCR

## Projektstruktur

- backend/  - Spring Boot REST API
- frontend/ - Next.js PWA
- deploy/   - Docker und VPS
- docs/     - Lastenheft, Pflichtenheft, Lernhandbuch
- .github/  - Automatisiertes CI/CD

## Entwicklungsumgebung

Lokal: Arch Linux mit VS Code
Server: Debian 13 auf netcup

## Deployment

Push auf main -> CI-Tests -> Docker Build ->
GHCR -> automatisches VPS-Deployment

## Status

Projektinitialisierung.
Produktives Instagram-Publishing ist noch deaktiviert.
