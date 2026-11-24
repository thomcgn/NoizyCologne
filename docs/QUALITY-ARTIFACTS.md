# Aufbewahrung von `.quality`-Artefakten

Das Quality Gate schreibt je Lauf ein Verzeichnis `.quality/run.*/` mit Logs und
`timings.json`. Diese Artefakte sind lokale, git-ignorierte Arbeitsdateien und
gehören nicht in Commits, Images oder Releases. Hintergrund und Grenzen der Läufe
stehen in [QUALITY.md](QUALITY.md).

## Retention

- **Erfolgreiche Läufe:** höchstens 30 Tage ab Erzeugung des Laufverzeichnisses.
- **Fehlgeschlagene oder abgebrochene Läufe:** bis die Ursache behoben ist,
  danach ebenfalls spätestens nach 30 Tagen entfernen.
- Einzelne `timings.json` vergleichbarer Läufe dürfen länger als Messwert
  aufgehoben werden; Logs nicht unbegrenzt ansammeln.

Das Gate löscht selbst nichts: Ein Lauf kann gerade für die Diagnose eines
offenen Fehlers benötigt werden. Die Entfernung erfolgt bewusst manuell und nur
in der lokalen Arbeitskopie, nie an Produktionsdaten.

## Vertraulichkeit

Logs können HTTP-Testdaten, Header, Antworttexte und Pfade aus Integrations- und
Browser-Tests enthalten. Vor Weitergabe in Issues, Pull Requests oder Chats
prüfen und nur bereinigte Auszüge veröffentlichen; keine Secrets, Token oder
echten Nutzerdaten übernehmen.
