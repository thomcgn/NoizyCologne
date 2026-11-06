# Quality-Gate-Laufzeiten, 06.11.2026

Gemessen wurde auf dem vorhandenen Linux-Entwicklungsrechner mit lokalem JDK 25
und Node 26; GitHub Actions verwendet Java 21/Node 24. Warme Dependencies sind
kein Maß für einen erstmaligen Download. Einzelmessungen sind keine garantierte
Laufzeit und keine statistische Benchmarkserie.

## Vorher: wo sich Arbeit wiederholte

Vor der Umstellung liefen beim manuellen Abschluss FULL, im Commit-Hook erneut
FULL und auf main CI-FULL sowie GHCR-FULL parallel. Im Docker-Build wurden die
Produktionspakete anschließend innerhalb ihrer eigenen Buildumgebung erstellt.
Ein PR-Lauf kam gegebenenfalls zusätzlich dazu. Insbesondere letzterer kann einen
anderen Merge-Commit prüfen und darf daher nicht pauschal entfallen.

| Vorherige Messung | Dauer | Herkunft / Einschränkung |
| --- | ---: | --- |
| Lokales FULL `run.QgtkPj` | ca. 148,89 s | Abstand erster Log-Erstellung bis letzter Log-Änderung |
| Lokales FULL `run.moLeQb` | ca. 169,52 s | gleicher historischer Dateizeitvergleich |
| Lokales FULL `run.KxaTyK` | ca. 177,84 s | gleicher historischer Dateizeitvergleich |
| CI FULL | 174 s | Schritte 07:18:46–07:21:40 UTC im [erfolgreichen Lauf 42179930090](https://github.com/thomcgn/NoizyCologne/actions/runs/42179930090) |
| CI npm ci | 49 s | derselbe Lauf, 07:17:57–07:18:46 UTC |
| CI Quality-Job insgesamt | 233 s | derselbe Lauf, 07:17:51–07:21:44 UTC |

Die historischen lokalen Gesamtzeiten sind Näherungen, keine neu gemessenen
monotonen Laufzeiten. Ein anderer Logsatz mit einem mehrstündigen Uhr-/Suspend-
Sprung wurde ausgeschlossen. Die Bestandsmessung belegt warme FULL-Läufe von
etwa 2,5–3 Minuten, keine allgemeine Dauer von 10–15 Minuten je einzelnem Lauf.
Mehrfache lokale/CI-/Image-Schritte und Erstdownloads können die gesamte Wartezeit
deutlich erhöhen.

Im historischen `run.moLeQb` entfielen ungefähr 47,19 s auf echte negative Java-
Fixtures, 43,90 s auf Maven Verify, 14,72 s auf den Datenbankstart, 12,06 s auf
javac/Maven-Kompilierung, 7,06 s auf ECJ, 8,30 s auf ESLint, 6,65 s auf Frontend-
Tests und 5,60 s auf den Frontend-Build. Diese Schritte bleiben in FULL erhalten.

## Messverfahren nach der Umstellung

Der neue Runner misst mit `time.monotonic_ns()`. Jede Ausführung enthält
`timings.json` mit Modus, Schrittzeiten, Exitcode und Gesamtzeit einschließlich
Datenbank-Cleanup. Es wird kein erfolgreicher Prüflauf als Ergebnis-Cache benutzt.

Repräsentative Dokumentations-, Frontend- und Backendänderungen werden in einem
temporären Git-Klon mit regulär installiertem QUICK-Hook geprüft. Die eigentlichen
Produktdateien im Arbeitsbranch bleiben unverändert. Fehlerfixtures zeigen
gesondert, dass Compiler-/Null-/Testfehler einen echten Commit verhindern.

## Tatsächlich gemessene QUICK-Läufe

Warmer temporärer Klon, regulärer QUICK-Hook installiert, echte Compiler/Tests.
Die positiven Messungen liefen vor der abschließenden parallelen FULL-Validierung.
Die Zeiten stammen aus der monotonen Messung des aufrufenden Testprogramms.

| Änderung | QUICK | Tatsächlicher Umfang |
| --- | ---: | --- |
| Dokumentation | 5,960 s | Diff/Repository/Syntax, keine Komponenten oder Docker |
| Frontend-CSS | 25,452 s | Typegen, TypeScript, Frontend-Regressionen; keine geänderte JS-/TS-Datei für ESLint; kein Build/Backend/Docker |
| Backend-Unit-Test | 45,529 s | inkrementelles javac, vollständiges ECJ, `PartyOnlyPolicyTests`; kein Frontend/Build/Docker |
| Erstmaliger breiter Werkzeug-Commit im Benchmarkklon | 135,026 s | regulärer QUICK-Hook mit Gate-Selbsttests, ECJ und negativen Compiler-Fixtures |

Die ersten drei kleinen Änderungen liegen in dieser Umgebung unter dem Ziel von
1–2 Minuten. Der breitere Werkzeug-Commit braucht weiterhin mehr Prüfaufwand.
Vergleich zu den historischen lokalen FULL-Läufen von etwa 149–178 s: Die
Einsparung ergibt sich aus bewusst kleinerem Prüfumfang, nicht aus geschwächten
Fehler-/Warnregeln. Kalte Downloads und größere Testsuiten können länger dauern.

## Reale Fehler- und Hook-Nachweise

Im selben temporären Klon blockierte der reguläre QUICK-Hook sechs tatsächliche
Commitversuche. Jeweils blieb `HEAD` unverändert; kein `--no-verify`, keine
abweichende Hook-Konfiguration. Die injizierten Fixtures wurden anschließend
entfernt, der Klon war zum Schluss Git-sauber.

| Absichtlich eingefügter Fehler | Blockierende Stufe |
| --- | --- |
| TypeScript-Typfehler | TypeScript |
| ESLint-Warnung bei erfolgreichem TypeScript | ESLint mit `--max-warnings=0` |
| Java-Typfehler | javac |
| Java-Compilerwarnung | javac mit Warnungsfehlern |
| Unsichere nullable Java-Verwendung | ECJ-/XML-Analyse |
| Fehlgeschlagene JUnit-Assertion | selektierter Backend-Test |

Das ergänzt die versionierten Scope-/Hook-/Deployment-Selbsttests und die
bestehenden echten negativen Java-/Frontend-Fixtures. Laufzeiten dieser negativen
Versuche sind keine Performancewerte: Spätere Versuche liefen parallel zur
abschließenden FULL-Prüfung.

**Infrastrukturdiagnosen:** Maven 3.10.0 meldete in den Backendläufen HTTP-401-
Warnungen beim automatischen Abruf von `/.meta/prefixes.txt` aus transitiven
Flyway-Repositories. Das sind keine javac-/ECJ-Diagnosen, dennoch ist daraus keine
absolute Warnungsfreiheit sämtlicher CLI-Ausgaben abzuleiten. Weder Compilerregeln
noch Repositoryfilter wurden abgeschwächt, um diese Meldungen zu verbergen.
Apache beschreibt die [automatische Prefix-Auflösung](https://maven.apache.org/resolver/remote-repository-filtering.html).

## Abschließende vollständige Validierung

Das optimierte FULL lief erfolgreich in **283,711 s** (monotone Gesamtzeit
inklusive Datenbank-Cleanup, Bericht `.quality/run.PbMcay/timings.json`).
Tatsächlich ausgeführt: **71 Gate-/Scope-/Hook-/Deployment-Selbsttests**, **11 echte
Java-Analyse-Testmethoden**, **127 Backend-Tests** (126 erfolgreich, ein explizit
optionaler historischer Offline-Test ohne privaten Export übersprungen), **22
Frontend-Tests**, **2 Frontend-Analyse-Testmethoden**, TypeScript, ESLint,
Backend-Paket und Next-Produktionsbuild. ECJ-Haupt-/Testberichte enthalten jeweils
**null Probleme einschließlich INFO**. Caddy-Validierung und actionlint waren
separat erfolgreich.

Dieser FULL-Lauf ist **nicht schneller** als die historischen FULL-Läufe. Er
enthält erheblich mehr Gate-Regressionen (71 statt zuvor 15), und anfangs liefen
zusätzliche reale negative Commitversuche im separaten Klon parallel. Allein
Gate-Selbsttests benötigten 80,858 s, echte Java-Fixtures 68,082 s; Maven Verify
43,599 s, Datenbankstart 15,568 s, Next-Build 15,666 s. Die Optimierung spart
wiederholtes FULL bei kleinen Änderungen und in GHCR; sie behauptet keine
Beschleunigung dieser einzelnen umfangreicheren FULL-Validierung.

Die CLI-Prüfungen ersetzen keinen direkten Blick in die laufende IDE. Eine
aktuelle Live-VS-Code-Problemliste wurde für diesen Auftrag nicht abgefragt.

## Verbliebene Kosten und bewusste Grenzen

QUICK spart Produktionsbuilds, ganze unbetroffene Komponenten und unnötige
Datenbankstarts. ECJ prüft bei Backendänderungen weiterhin alle aktuellen Haupt-
und Testquellen; Warnungsfreiheit wird dafür nicht aus einem alten Cache abgeleitet.
Bei Frontendänderungen bleiben TypeScript und die kleine gemeinsame React-Suite
vollständig. Werkzeugänderungen führen zusätzlich die Gate-Selbsttests aus.

Die BuildKit-Layercaches für Backend, Frontend und Landing haben getrennte
GHA-Scopes. Basisimages werden mit `pull: true` aufgelöst. Maven- und npm-Caches
beschleunigen Dependency-Auflösung; sie ersetzen keine Tests. Die Image-Builds
erstellen weiterhin ihre Produktionspakete in der jeweiligen Containerumgebung.
Ubuntu-/lokale Next-Artefakte werden nicht ungeprüft in Alpine wiederverwendet.

PR-FULL und main-FULL bleiben bei unterschiedlichen SHAs getrennt. GHCR verwendet
den erfolgreichen FULL-Nachweis derselben main-SHA und startet dafür keinen
zweiten parallelen FULL-Lauf. Feature-Pushes laufen gezielt über QUICK; für eine
unbekannte Push-Basis wird vorsichtshalber der Umfang erweitert.

Es gab im Rahmen dieses Feature-Branch-Auftrags keinen neuen GitHub-Deploymentlauf.
Zeitersparnisse der künftigen GHA-Layercaches werden daher nicht als gemessen
ausgegeben. Branch Protection muss zusätzlich angewandt werden, siehe
[QUALITY.md](QUALITY.md#erforderlicher-schutz-für-main).
