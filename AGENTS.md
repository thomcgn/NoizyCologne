# NoizyCologne repository instructions

Read `docs/PROJECT.md` and the instructions in the affected subdirectories before editing.
Keep existing local changes from other tasks intact. Do not push, deploy, or change production data without an explicit task authorizing it.

## Commit, Push und Pull-Request-Abnahme

Der Nutzer hat Commit und Push als regulären Abschluss jeder Aufgabe dauerhaft
beauftragt. Nach erfolgreichen erforderlichen Prüfungen nur die Änderungen der
jeweiligen Aufgabe committen und auf einen Feature-Branch pushen. Einen Pull
Request zur Abnahme erstellen oder den bestehenden Aufgaben-PR aktualisieren.
Alle Änderungen müssen per Pull Request abgenommen werden; keine direkten Pushes
auf `main`, kein eigenständiger Merge und kein Deployment. Die verpflichtende
FULL-Prüfung für den exakt akzeptierten Commit bleibt unverändert.

## Mandatory Quality Strategy

These rules apply to every future Codex task, including features, fixes, refactoring,
migrations, APIs, components, tests and configuration changes:

1. Inspect the relevant repository state, instructions and current diagnostics before editing.
2. After a coherent change, run only the directly affected tests and necessary static/compiler checks, according to the scope strategy below. Reuse still-valid results within the task.
3. Fix project errors and warnings, including conversion, unchecked-conversion and null-type-safety diagnostics. Keep the strict compiler settings and rerun affected checks after corrections; do not add blanket suppressions.
4. During development, use targeted tests and necessary compiler/type checks. The mandatory pre-commit hook runs QUICK; do not run QUICK manually immediately before it. STANDARD module checks require the scope or a concrete diagnostic reason; FULL runs in GitHub Actions, locally only when explicitly necessary for diagnosis.
5. Do not commit or report code complete after a failed or missing required check. Never silently skip checks or report an unexecuted check as passing.
6. Do not disable inspections, weaken warning rules, add blanket suppressions or remove working functionality to make checks pass.
7. Add appropriate tests for new behavior and regressions.
8. Preserve unrelated working-tree changes. Stage and commit only the task's changes; inspect the final diff and `git diff --check`.
9. Use reproducible CLI diagnostics. Codex Cloud may commit without access to the live VS Code Problems panel when the required CLI checks pass. Report CLI results separately from inaccessible IDE diagnostics; never claim an IDE check from CLI evidence alone. Accessible concrete diagnostics still need investigation.
10. End each task with a short quality summary: checks run, outcome, warnings/limitations and commit status.

The binding target remains **zero errors and warnings in the checks that run**.
Conversion and null-type-safety diagnostics, including ECJ `INFO`, are blocking.
QUICK is scoped feedback, not proof that the entire project passed FULL.
Before merging into `main` or deploying, GitHub Actions must complete
`scripts/check-quality.sh --full` successfully for the exact commit being accepted.
Missing, running, failed, cancelled or skipped mandatory checks are not approval.
An earlier result for another commit, including a PR head versus its merge commit,
must never authorize publication. Required branch protection must be configured;
workflow files alone do not prevent an administrator from merging unchecked code.
Do not use `--no-verify`, alternate hook paths or skip flags to bypass the gate.

Required local database integration tests run only against a disposable isolated test database, never inherited development/production connection settings. STANDARD/FULL create their own PostgreSQL database. QUICK never starts a database container and explicitly defers database integration tests; all current Java test sources remain compiled/analyzed. Targeted integration results supplement QUICK and do not replace mandatory CI FULL.
Never point tests at the development or production database.
See `docs/QUALITY.md` for prerequisites,
Java/IDE coverage limits and hook installation. Configured JSpecify `@NullMarked` defaults
and explicit null annotations remain part of the mandatory analysis; this is not a complete
JSpecify verifier. `@NullUnmarked` resets are not fully modeled and new scopes require
separate compatible validation. Use explicit null checks at unannotated library boundaries;
do not invent non-null guarantees to make diagnostics disappear. Passing either or both
compiler versions does not establish live IDE warning freedom. Existing frontend-specific
`AGENTS.md` instructions remain in force.

## Änderungsumfang und lokale Prüfungen

- **Klein** (CSS, Texte, einfache Komponenten, isolierte Fehlerkorrektur): direkt
  betroffene Tests und notwendige statische/Compiler-/Typprüfungen. Keine vollständige
  Backendtestsuite und keine unnötige Browserprüfung.
- **Mittel** (Discovery-Services, API-Endpunkte, Datenverarbeitung): betroffene Unit-
  und Integrationstests; nötige Datenbanktests ausschließlich isoliert. Keine
  wiederholte vollständige Modulsuite.
- **Umfangreich** (Migrationen, Authentifizierung, Infrastruktur, gemeinsame
  Build-Konfiguration): erforderliche Modulprüfungen und risikoabhängige
  Integrationstests. Betroffene Module begründen; kein pauschaler Backend- plus
  Frontend-Durchlauf. FULL bleibt in GitHub Actions.

Für jeden Umfang läuft QUICK durch den bestehenden Commit-Hook. Der Umfang richtet
sich nach den betroffenen Abhängigkeiten und Risiken, nicht allein nach Zeilenzahl.
Erforderliche Sicherheits- und Regressionstests bleiben verpflichtend. Details und
Grenzen der Modi stehen in [docs/QUALITY.md](docs/QUALITY.md).

## Keine vermeidbaren Wiederholungen

- Nur relevante Projektabschnitte und Unterverzeichnisregeln lesen; keinen erneuten
  Gesamtaudit für kleine Aufgaben durchführen.
- Bereits bestandene Prüfungen bei unverändertem relevantem Code, Teststand,
  Konfiguration und Abhängigkeiten nicht manuell wiederholen. Im Aufgabenstand
  Befehl, Ergebnis, geprüften Codezustand und Grenzen kurz festhalten; keine neue
  Dokumentationsdatei für jede kleine Aufgabe erforderlich.
- Nach einem Fehler nur die betroffene Prüfung nach fachlicher Korrektur erneut
  ausführen. Änderungen an gemeinsamen Eingaben können weitere Ergebnisse ungültig
  machen; dann nur diese betroffenen Prüfungen wiederholen. Ein nachträglich
  entwertetes Ergebnis gilt nicht als bestanden für den neuen Stand.
- Keine zusätzlichen lokalen STANDARD-/FULL-Läufe ohne konkreten, festgehaltenen
  Bedarf. Identische Builds innerhalb einer unveränderten Arbeitsphase nicht
  wiederholen. STANDARD ist eine vollständige Modulprüfung, kein Standardabschluss
  jeder Aufgabe.
- Verpflichtende Hook- und CI-Prüfungen laufen weiterhin vollständig gemäß ihrer
  bestehenden Auswahl. Frühere lokale Erfolge sind kein Grund, diese zu umgehen;
  die Vermeidung doppelter Arbeit betrifft zusätzliche manuelle Läufe.
- STANDARD nutzt sichere inkrementelle Kompilierung; FULL kompiliert Java sauber.
  Löschungen und gemeinsame Compilereingaben invalidieren alte Ausgaben. Frische
  ECJ-Berichte, strikte Compilerregeln und negative Prüfer-Fixtures gemäß Gate
  bleiben verpflichtend. QUICK ist kein vollständiger Integrationsnachweis.

## Browser und Auftragsgrenzen

- Zusätzliche lokale Browserprüfungen nur bei sichtbaren UI-Änderungen oder
  browserabhängigem Verhalten. Standard: **1440 px Desktop und 390 px Smartphone**.
  Weitere Größen nur bei einem konkreten Fehler oder ausdrücklichem Auftrag.
- Bestehende Fixtures und Browserwerkzeuge verwenden. Für einzelne kleinere
  Änderungen keine neue Screenshot-Infrastruktur entwickeln. Verpflichtende
  Browser-Worker-/Sandbox-Tests im Gate bleiben unberührt.
- Auftrag gezielt abschließen: keine ungefragten Features, umfassenden Refactorings,
  neue Infrastruktur, zusätzliche Dokumentation oder Tests für unbetroffenes
  Verhalten. Erforderliche Sicherheits- und Regressionstests gehören zum Auftrag.
- Nach erfolgreicher Implementierung und den erforderlichen Prüfungen nur die
  Aufgabenänderungen committen, auf den Feature-Branch pushen und den PR erstellen
  oder aktualisieren. Kein eigenständiger Merge, kein Deployment. Im Abschluss
  Prüfungen, Grenzen, Commit-/PR-Status und noch ausstehende CI klar nennen.
