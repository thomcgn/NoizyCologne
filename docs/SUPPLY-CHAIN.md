# Supply-Chain-Baseline

Diese Datei beschreibt die organisatorische Policy zu den beiden eigenständigen
Konfigurationen `.github/dependabot.yml` und `.github/workflows/security.yml`.
Das bestehende Quality-Gate (`ci.yml`, `quality-quick.yml`, `quality-full.yml`,
`Full quality gate`) bleibt unverändert; der Security-Workflow ist kein weiterer
Pflichtcheck des Gates und löst kein Deployment aus.

## Was läuft

- **Dependabot** prüft wöchentlich Maven (`backend/`), npm (`frontend/`),
  GitHub Actions und Dockerfiles (`backend/`, `frontend/`, `deploy/landing/`,
  `deploy/smoke/`, `browser-worker/`). Minor-/Patch-Updates sind gruppiert,
  Major-Updates kommen einzeln. Es gibt keine automatischen Merges: jede PR
  durchläuft Review und die gewohnte Qualitätsprüfung.
- **Security-Workflow** läuft nur manuell (`workflow_dispatch`) oder wöchentlich
  per `schedule`. `npm audit --omit=dev` prüft die Produktionsdependencies,
  OSV-Scanner in der fest gepinnten Version `v2.7.1` prüft `backend/pom.xml`
  (inkl. transitiver Maven-Abhängigkeiten) und `frontend/package-lock.json`.
  Beide Scans scheitern bei Findings *und* bei Toolfehlern; es gibt keine
  Severity-Schwelle, kein `continue-on-error`, keine Secrets und keinen Upload
  von Artefakten. An die öffentlichen Dienste npm-Registry, osv.dev und deps.dev
  gehen nur Paketnamen und Versionen, keine Tokens und keine Projektdaten.

## Findings, Severity und Ausnahmen

- Jeder gemeldete Fund – unabhängig von der Severity – wird wie ein Fehler
  behandelt und muss behoben oder nachweislich als False Positive belegt
  werden. Es wird keine Schwelle gesetzt, die niedrigere Findings versteckt.
- Eine Ausnahme benötigt zwingend **Owner, Begründung und Ablaufdatum**. Der
  Owner ist der benannte Maintainer des betroffenen Bereichs; ohne Benennung
  gibt es keine Ausnahme. Die maximale Laufzeit beträgt 30 Tage, danach ist der
  Fund zu beheben oder die Ausnahme ausdrücklich zu erneuern.
- Aktuell sind keine Ausnahmen erteilt:

  | Fund | Owner | Ablaufdatum | Begründung |
  | --- | --- | --- | --- |
  | – | – | – | – |

## Caches sind kein Nachweis

Maven-, npm- und Docker-Layercaches beschleunigen nur den Aufbau. Sie sind
weder ein Nachweis für eine bestandene Qualitätsprüfung noch für einen
sauberen Dependency-Status. Freigaben hängen unverändert vom exakt geprüften
Commit und den bestehenden GitHub-Checks ab.

## Bekannte Inkonsistenz und Follow-up

- `ghcr.yml` verwendet überwiegend `actions/checkout@v4`, an einer Stelle im
  Deployment-Job jedoch `actions/checkout@v5`. Das wird hier nur dokumentiert;
  die Datei wird in diesem PR nicht geändert.
- Follow-up (separater PR, hier bewusst nicht massenhaft umgesetzt): GitHub
  Actions und Basisimages auf commit-SHA bzw. Digest pinnen. Der neue
  OSV-Scanner ist bereits auf die konkrete Version `v2.7.1` fixiert.
- Optionales Follow-up: Image-Scan der gebauten Container auf Basis derselben
  fest versionierten Scanner-Komponente; in diesem PR nicht enthalten.
