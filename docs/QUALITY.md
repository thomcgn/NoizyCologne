# Verbindliches Quality Gate

Die Root-`AGENTS.md` gilt für alle Codex-Aufträge, zusätzlich zu Regeln in
Unterverzeichnissen. Nach zusammenhängenden Änderungen laufen passende Tests und
Analysen. Vor lokalen Commits genügt **QUICK** beziehungsweise eine geeignete
Komponentenprüfung; der installierte Commit-Hook führt QUICK aus. Vor Merge nach
`main` und Deployment bleibt **FULL für den exakten Commit** verbindlich.

Fehler, Compilerwarnungen, Conversion- und Null-Type-Safety-Diagnosen werden behoben.
Auch ECJ-Probleme mit Schweregrad `INFO` blockieren. Keine pauschalen Suppressions,
Regelabschwächungen, entfernten Fachfunktionen oder Hook-Umgehungen. QUICK prüft
bewusst weniger als FULL und ist keine vollständige Freigabe des Projekts.

## Aufruf und Voraussetzungen

```sh
scripts/check-quality.sh --quick       # Entwicklung und lokaler Commit
scripts/check-quality.sh --backend     # STANDARD: vollständige Backend-Prüfung
scripts/check-quality.sh --frontend    # STANDARD: vollständige Frontend-Prüfung
scripts/check-quality.sh --full        # beide Komponenten, vor Merge/Deployment
```

Ohne Argument läuft weiterhin FULL. Alle Modi benötigen Bash, Git, Python 3 und
`tee`. Backend-Prüfungen benötigen JDK mindestens 21 und den Maven-Wrapper;
Integrationstests zusätzlich einen erreichbaren Docker-Daemon. Frontend benötigt
eine unterstützte Node-Version und einmalig `cd frontend && npm ci`. CI verwendet
Java 21 und Node 24. Beim ersten Lauf benötigen Maven/npm Zugriff auf die
Abhängigkeitsquellen. ECJ bleibt auf `org.eclipse.jdt:ecj:3.46.100` festgelegt.
Fehlende benötigte Werkzeuge/Dependencies führen zum Fehler, nicht zum Überspringen.

| Prüfung | QUICK | STANDARD / FULL |
| --- | --- | --- |
| Diff, Index, Secrets, Konflikte, Shell-Syntax | immer | immer |
| Gate-/Hook-/Deploy-Selbsttests | bei Werkzeug-/Workflowänderungen | immer |
| Backend javac | inkrementelles `test-compile`, bei Löschungen/Konfigurationsänderungen mit `clean` | `clean test-compile` |
| Backend ECJ einschließlich Tests und XML-Auswertung | bei Backend-Betroffenheit vollständig | vollständig |
| Echte negative Java-Fixtures | bei Backend plus Werkzeugänderung | bei Backend immer |
| Backend-Tests | betroffene Klassen/Pakete über Surefire | gesamtes `verify` mit Paketierung |
| PostgreSQL | nur für ausgewählte Integrationstests | bei Backend immer |
| Next-Typegen und TypeScript | bei Frontend-Betroffenheit vollständig | bei Frontend vollständig |
| ESLint mit null Warnungen | geänderte JS-/TS-Dateien, bei gemeinsamem Setup vollständig | bei Frontend vollständig |
| Frontend-Regressionstests | bei Frontend-Betroffenheit aktuelle gemeinsame Suite | bei Frontend vollständig |
| Echte negative TypeScript-/ESLint-Fixtures | bei Frontend plus Werkzeugänderung | bei Frontend immer |
| Frontend-Produktionsbuild | entfällt | bei Frontend immer |

STANDARD bezeichnet die bestehenden Modulschalter, keinen neuen `--standard`-
Schalter. FULL führt beide Module aus und beendet sich bei jeder fehlgeschlagenen
Pflichtprüfung mit Fehler. `skipTests=false` und `maven.test.skip=false` bleiben
explizit gesetzt; es gibt keinen Skip-Schalter.

## Wie QUICK seinen Umfang bestimmt

`scripts/quality-scope.py` vereinigt Änderungen im **aktiven Index**, Arbeitsbaum
und nicht ignorierte neue Dateien. Es liest auch den bei Teilcommits gesetzten
`GIT_INDEX_FILE`, verändert ihn aber nicht. Bei CI-Pushes ergänzt
`QUALITY_BASE_REF` den Vergleich dieses Ausgangscommits mit `HEAD`; eine fehlende,
Null- oder nicht auflösbare ausdrücklich gesetzte Basis erzwingt konservativ beide
Module und die Werkzeugtests. Ein sauberer Checkout darf nicht irrtümlich als
ungeändertes Projekt durchgehen.

Reine Dokumentation benötigt weder Java/Node noch Docker. Backend-Teständerungen
wählen die betroffene Testklasse aus; Änderungen an Hauptklassen wählen bestehende
Referenztests und gegebenenfalls das Paket. Gemeinsame Konfigurationen und
Ressourcen erweitern die Auswahl. Unbekannte Eingaben werden konservativ behandelt;
eine `.txt` unter `backend/src` ist beispielsweise keine reine Dokumentation.
QUICK kann nicht alle indirekten fachlichen Auswirkungen kennen. Diese werden vor
dem Merge zusätzlich von FULL geprüft.

javac nutzt in QUICK vorhandene Kompilate; gelöschte/umbenannte Quellen oder gemeinsame
Backend-Konfigurationen erzwingen saubere Ausgaben. ECJ analysiert jedes Mal alle
aktuellen Haupt- und Testquellen mit frisch erzeugten Berichten. Ein alter sauberer
XML-Bericht oder ein erfolgreicher Exitcode trotz Problemen kann keinen Erfolg
vortäuschen. Maven-/npm-Caches enthalten Dependencies, keine Qualitätsfreigaben.

Compiler lesen den Arbeitsbaum. Wenn dieselbe Code-/Werkzeugdatei gleichzeitig
abweichend gestagt und ungestagt ist, verweigert das Gate deshalb die Freigabe.
Die gewünschte vollständige Dateiversion stagen oder Änderungen aufteilen. Der
Index bleibt erhalten; Dokumentation darf weiterhin teilweise gestagt werden.
Eine gestagte Löschung mit neu angelegter ungetrackter Codeversion wird ebenso
abgelehnt. Das Gate erzeugt keinen alternativen Commit-Snapshot.

Jeder Lauf schreibt Schrittzeiten und Gesamtzeit mit monotoner Uhr nach
`.quality/run.*/timings.json`, auch bei Fehlern. Der Erfolg wird erst nach dem
Entfernen einer gestarteten Testdatenbank ausgegeben. Logs können HTTP-Testdaten
enthalten; vor Weitergabe prüfen. Siehe [Laufzeitvergleich](QUALITY-BENCHMARK.md).

### Datenbanksicherheit und optionale historische Diagnose

Das Skript übernimmt **keine vorhandene `DB_URL`**. Es startet bei Bedarf einen
eigenen PostgreSQL-17-Alpine-Container ohne vorhandene Volumes, bindet einen
zufälligen Port ausschließlich an 127.0.0.1 und verwendet eigene Testzugangsdaten.
Es wartet auf den finalen TCP-Server, nicht initdbs temporären Unix-Socket-Server.
Discovery-Scheduler bleiben deaktiviert. `MEDIA_ROOT` zeigt ins Laufverzeichnis; einzelne bestehende Flyer-Integrationstests überschreiben es ausdrücklich mit eigenen `backend/target/test-*-flyers`-Verzeichnissen.
Container und anonyme Daten werden auch bei Fehler/Abbruch entfernt. Ein
fehlgeschlagenes Cleanup verhindert die Erfolgsmeldung.

Bei ausgewählten Unit-Tests ohne Datenbank wird eine absichtlich unerreichbare
Loopback-Verbindung vorgegeben. Ein übersehener Datenbankbedarf muss fehlschlagen,
statt eine geerbte Entwicklungs-/Produktionsverbindung zu verwenden.

`ReadOnlyDiscoveryDiagnosisTests` bleibt ein **optionaler** Offline-Test für einen
privaten historischen Export. Ohne `NOIZY_DISCOVERY_SNAPSHOT` wird ausdrücklich
„NOT RUN“ und ein übersprungener JUnit-Test gemeldet. Ein bewusst gesetzter
ungültiger Pfad führt zum Fehler. Keine verpflichtende Prüfung entfällt dadurch.

## Java-Diagnosen und ehrliche Grenzen

Die installierte Jackson-Version wurde am lokalen Maven-Graph/Bytecode geprüft:
**3.1.7**. Ihre veralteten `asText()`-/`asText(default)`-Aliasse wurden durch
`asString()`/`asString(default)` und `isTextual()` durch `isString()` ersetzt.
Diese Methoden sind in der installierten Version vorhanden; bestehende
Extraktions-/Programmlisten-Tests sichern das Verhalten ab.

Reproduzierter Ausgangsbestand: **21 Warnungen** mit der vorhandenen lokalen
IDE-Konfiguration, **43 Warnungen** im zusätzlichen Profil mit expliziten
JSpecify-Null-Annotationen: 11 Deprecated-Aufrufe, vier unbenutzte Imports,
eine fehlende Serialisierungs-ID und 27 Null-Safety-/Typflussdiagnosen.
Zusätzlich fand javac auf dem lokalen JDK 25 eine `this-escape`-Warnung im Lexikon-Konstruktor: Der Konstruktor rief eine überschreibbare Methode auf. Die Initialisierung verwendet jetzt einen privaten Helfer; die öffentliche Refresh-API bleibt erhalten und ein Regressionstest schützt den Fall. Insgesamt wurden **44 projektinterne Java-Warnungen** bereinigt. Nullable Getter werden einmal gelesen und
geprüft, optionale Medien ohne nullable `Optional<T>.orElse`-Typkonflikte behandelt,
Repository-/Eventlisten deklarieren ihre tatsächlich nicht-null Elemente.
Fachliche Assertions und Fehlerprüfungen wurden erhalten.

**CLI und Live-IDE-Nachweis getrennt behandeln:** ECJ 3.46.100 ist eine eigenständige,
fest versionierte Batch-Analyse; die lokal installierte Java-Erweiterung verwendet
einen anderen JDT-Snapshot (3.46.200). Es ist keine automatisierte Schnittstelle zur
laufenden Problems-Ansicht angebunden; eine direkte Sichtprüfung muss den aktuellen
Projektstand zeigen. IDE-spezifische Erweiterungen, Projektmodell,
Annotation-Inferenz und Zusatz-Inspections können weitere Diagnosen erzeugen.
Ein erfolgreicher Batch-Lauf erlaubt deshalb allein keine Behauptung „VS Code
zeigt null Probleme“. Aktuelle erreichbare IDE-Diagnosen sind ebenfalls zu prüfen;
fehlender Live-Zugriff muss im Abschluss benannt werden. Bekannte IDE-Warnungen
bleiben blockierend und dürfen nicht mit dem Hinweis auf ein grünes CLI-Gate
übergangen werden. Codex Cloud darf bei erfolgreichen erforderlichen CLI-Prüfungen auch ohne Live-IDE-Zugriff committen. Der fehlende Zugriff wird ausdrücklich benannt.

`.vscode/settings.json` verwendet dieselben Java-Präferenzen und legt JSpecify als
primäre Annotationsfamilie fest. Die Spring-Inspection „Convert @Autowired field
into Constructor Parameter“ ist ebenfalls blockierend (`ERROR`). Die vier beim
direkten IDE-Abgleich gefundenen Felder in `CalendarEntryService`,
`EditorialPostDraftService` und `DiscoveryController` werden jetzt über die
Konstruktoren injiziert. Bei mehreren absichtlich vorhandenen Konstruktoren bleibt
`@Autowired` am ausgewählten Konstruktor zulässig, etwa im `GenreLexicon`.

Das Profil modelliert `org.jspecify.annotations.NullMarked` als primären
Package-/Typ-Default und erkennt explizite JSpecify-`NonNull`/`Nullable`-Verträge.
Die früheren Spring-`NonNullApi`-/`NonNull`-/`Nullable`-Annotationen bleiben als
zusätzliche Verträge aktiv. Damit werden die tatsächlichen JSpecify-Defaults
moderner Spring-Bibliotheken ausgewertet; sie dürfen nicht als unbekannte
Legacy-Verträge behandelt werden.

Das ist **kein vollständiger JSpecify-Verifier**. Insbesondere wird der Reset eines
`@NullMarked`-Defaults durch `@NullUnmarked` nicht vollständig ausgewertet. Neue
`@NullUnmarked`-Scopes im Projekt müssen daher gesondert mit einer dafür geeigneten
Analyse validiert werden; aus diesem Profil allein folgt keine Freigabe dafür.
Diese Modellgrenze ist keine Ausnahme für vorhandene Compiler-/IDE-Warnungen:
konkrete Diagnosen bleiben zu beheben. Auch übereinstimmend erfolgreiche Läufe mit
beiden Compiler-Versionen ersetzen keinen aktuellen Nachweis der Live-IDE-Diagnosen.

An unannotierten JDK-/Framework-Grenzen werden erforderliche Rückgaben mit echten
Null-Prüfungen abgesichert, beispielsweise `Objects.requireNonNull(...)`.
Nullable Werte behalten ihre Bedeutung; sie werden nicht durch pauschale
Bibliotheksmodelle oder Annotationen als nicht-null erklärt. Für Jackson-Rückgaben
weist `JsonValues.readRequired(...)` den JSON-Wert `null` ausdrücklich zurück.
Positive und negative Compiler-Fixtures prüfen JDK-Generik, binäre
`@NullMarked`-Package-Defaults und nullable flexible Typvariablen. Die Batch-Analyse
ist dennoch kein Nachweis sämtlicher Inspections einer laufenden VS-Code-Installation;
ein nicht zugänglicher IDE-Nachweis wird nicht als erbracht ausgegeben.

Der frühere ECJ-Lauf tolerierte zunächst **98**, nach Event Change Monitoring
**109 Informationshinweise** (19 main, 90 test) an Null-/Generikgrenzen. Dieser
Bestand ist seit der Verschärfung ausdrücklich **keine zulässige Ausnahme** mehr.
Die unsichere Weitergabe annotierter Generics wird über
`org.eclipse.jdt.core.compiler.problem.annotatedTypeArgumentToUnannotated=warning`
blockiert. Für unsicher interpretierte Bibliotheksrückgaben gilt
`org.eclipse.jdt.core.compiler.problem.nonnullTypeVariableFromLegacyInvocation=error`:
eine echte Compiler-Fixture zeigt, dass ECJ 3.46.100 die Einstellung `warning` in
diesem Fall weiterhin als INFO ausgeben kann. Zusätzlich schützt die XML-Prüfung
vor jedem weiteren nichtfatalen Compilerproblem.

Die fachliche Korrektur erhält Null-Verträge durchgängig und prüft Werte an
Bibliotheksgrenzen ausdrücklich, statt sie blind als nicht-null anzunehmen.
Inspections, Annotationen und Schweregrade dürfen dafür nicht abgeschwächt,
Informationshinweise nicht ausgeblendet und Suppressions nicht pauschal ergänzt
werden. Auch Warnungen in Tests sind zu beheben.

Referenzen: [ECJ-Batch-Optionen](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/tasks/task-using_batch_compiler.htm),
[Jackson-JsonNode-Aliasse](https://github.com/FasterXML/jackson-databind/blob/3.x/src/main/java/tools/jackson/databind/JsonNode.java).

### Secret- und Konfliktprüfung

`scripts/check-repository.py` liest sowohl jeden getrackten Arbeitsbaumstand als
auch den tatsächlichen Index-Blob. Ein im Index liegender Token lässt sich dadurch
nicht durch einen sauberen Arbeitsbaum verdecken. Es meldet nur Pfad, Version und
Regel, **keine geheimen Inhalte**. Geprüft werden Konfliktmarker, eingecheckte
Nicht-Beispiel-`.env`-Dateien, private Schlüssel sowie eindeutige Tokenformate von
GitHub, AWS, Slack und Google. Ungetrackte Dateien werden nicht eingecheckt oder
verändert; neue Dateien werden spätestens nach dem Stagen mitgeprüft.

Die Mustersuche ist kein vollständiger Secret-/Entropy-/DLP-Scanner. Beliebige
Passwörter, unbekannte Providerformate und zur Laufzeit zusammengesetzte Secrets
kann sie nicht zuverlässig erkennen. Deshalb gehört die manuelle Prüfung des
finalen Diffs weiterhin zwingend zum Ablauf. Keine pauschale Aussage „keine Secrets“.

## Hook aktivieren, ohne vorhandene Hooks zu verlieren

Bei jedem neuen Klon einmal:

```sh
scripts/install-hooks.sh
git config --get core.hooksPath
# .githooks
```

Der Installer prüft vorhandene `core.hooksPath`-Werte und Dateien in den bisherigen
Default-Hooks. Andere Hooks werden **nicht** unbemerkt ersetzt: Der Installer bricht
ab und verlangt ihre ausdrückliche Integration. Bereits eingerichtete `.githooks`
sind idempotent. Die versionierte `.githooks/pre-commit` ruft immer `--quick` auf,
manipuliert keine gestagten Dateien und führt weder Commit noch Push aus.

Bei Problemen: tatsächliche Fehlermeldung/Log untersuchen, fachlich korrigieren,
betroffene Checks wiederholen und vor dem Commit QUICK erfolgreich abschließen. Bei defekter
Prüfumgebung die Ursache dokumentieren und die Umgebung reparieren; nicht
`--no-verify` verwenden. Keine ungetestete Änderung als abgeschlossen melden.

## Selbsttests und CI

Die Tests unter `scripts/tests` prüfen echte negative Java-/Null-/Deprecated-/Import-,
javac-Warnungs-, TypeScript-, ESLint-Warnungs- und JUnit-Fixtures. Zusätzlich
prüfen echte ECJ-Fixtures den Verlust von Null-Annotationen und die unsichere
Interpretation generischer Bibliotheksrückgaben; erhaltene Annotationen und explizit
geprüfte Rückgaben müssen ohne Diagnosen kompilieren. Die XML-Regressionen sichern,
dass selbst ein erfolgreicher Compiler-Exit mit INFO, ein fehlender Bericht oder
ein alter sauberer Bericht keinen falschen Erfolg erzeugen kann. Weiterhin
simulieren isolierte Mini-Repositories die Werkzeug-Rückgabecodes, fehlende
Abhängigkeiten und den vollständigen Hook-Ablauf. Ein realer temporärer Git-Commit
wird bei Fehler blockiert und bei Erfolg erlaubt; der Index wird dabei nicht
verändert. Weitere Tests schützen vorhandene Hook-Konfigurationen und erkennen
einen nur im Index versteckten Token. Diese Simulationen verändern keine
Produktionsdaten und gelten nicht als Ersatz für die echten Projektprüfungen.

Die temporären Test-Repositories erhalten eine eigene Git-Umgebung. Insbesondere
darf der bei einem Teilcommit gesetzte `GIT_INDEX_FILE` nicht in fremde
Test-Repositories gelangen. Der echte Hook prüft weiterhin den vom Commit
bereitgestellten Index; Teilcommits müssen fremde gestagte Dateien erhalten.

### GitHub Actions und Freigabe derselben SHA

- `quality-quick.yml`: Push auf Feature-Branches, QUICK mit Commitbereich; Node/npm
  und Java nur für betroffene Module. Eine unbekannte Push-Basis erweitert den Umfang.
- `ci.yml`: Pull Requests nach `main` und Push auf `main`, FULL über
  `quality-full.yml`, einschließlich Caddy-Validierung. Der eindeutige erforderliche
  Check **Full quality gate** läuft mit `always()` und verlangt ausdrücklich
  `success` des vollständigen Vorgängers. `skipped`, `cancelled`, `failure` und
  unvollständige Läufe gelten nicht als Erfolg.
- `ghcr.yml`: automatisch erst nach abgeschlossenem erfolgreichen CI-Push-Lauf auf
  `main`. `quality-deployment.py` validiert Workflowpfad, Repository, Auslöser,
  Branch, neueste Run-ID/Attempt, exakte SHA, FULL-/Caddy-Schritte und Checkstatus.
  Bei Automatik muss die geprüfte SHA noch aktueller `main` sein. Checkout,
  Image-Tags und Serverdeployment verwenden durchgängig diese SHA, nicht die
  möglicherweise inzwischen geänderte `github.sha` des `workflow_run`-Kontexts.
- Manuell: Ein abgeschlossener vertrauenswürdiger CI-Push-auf-main-Lauf derselben
  SHA darf wiederverwendet werden. Sonst läuft FULL im eigenen Workflow. Ein
  manueller Feature-Branch-Aufruf prüft konservativ erneut FULL; sein QUICK-Erfolg
  autorisiert kein Deployment. Der Nachweis wird vor Publish und SSH erneut geprüft.

Ein PR prüft normalerweise einen synthetischen Merge-Commit. Sein Erfolg
berechtigt **nicht** zur Veröffentlichung eines abweichenden späteren main-Commits.
Deshalb bleiben PR-FULL und main-FULL erforderlich, wenn ihre SHAs verschieden sind.
Entfernt ist der frühere parallele zweite FULL-Lauf in GHCR für dieselbe main-SHA.
Die bestehende GHCR-/SSH-Auslieferung mit Smoke-Tests und Rollback bleibt erhalten.

Maven- und npm-Dependency-Caches bleiben aktiv. Es gibt keinen Cache „FULL war
irgendwann grün“; nur der streng validierte GitHub-Lauf derselben SHA kann als
Freigabe dienen. Getrennte GHA-Layercaches für Backend, Frontend und Landing beschleunigen Docker-Builds; `pull: true` löst Basisimages aktuell auf. Diese Caches sind kein Qualitätsnachweis. Die Produktionsimages
bauen ihre Pakete innerhalb der jeweiligen Containerumgebung erneut: Ein lokales
Next-Artefakt in das Alpine-Image zu kopieren, ohne Toolchain-/Plattformbindung,
wäre keine sichere Optimierung. Qualitätsprüfungen und Image-Erstellung werden
daher weiterhin getrennt nachgewiesen.

### Erforderlicher Schutz für `main`

Workflowdateien allein sperren keine Merge-Schaltfläche. Der vorbereitete
[Schutzantrag](../.github/branch-protection-main.json) verlangt Pull Requests,
aktuelle erforderliche Checks, **Full quality gate** ausschließlich von GitHub
Actions (App-ID 15368), Einbeziehung von Administratoren und verbietet Force-Push
und Löschen. Die Zahl zusätzlicher Review-Freigaben bleibt null; FULL ist trotzdem
verbindlich. Keine PR-Bypass-Ausnahmen konfigurieren.

Aktuellen GitHub-Stand ausschließlich lesend prüfen:

```sh
python3 scripts/check-branch-protection.py
```

**Stand der Bestandsaufnahme am 06.11.2026:** `main` war ungeschützt, ohne Rulesets.
Diese Feature-Branch-Änderung aktiviert keine Repository-Einstellung. Solange der
Schutz nicht angewandt und der lesende Check erfolgreich ist, ist das
Abnahmekriterium „kein Merge ohne FULL“ organisatorisch und technisch **noch offen**.
Der benötigte Schutz muss im privaten Repository auch vom GitHub-Tarif unterstützt
werden; die gelesene API lieferte keine Tarifauskunft.

Nach Freigabe durch einen Repository-Administrator lässt sich die vorbereitete
Konfiguration anwenden (ersetzt vorhandene Branch-Protection-Einstellungen; zuvor
prüfen):

```sh
gh api --method PUT repos/thomcgn/NoizyCologne/branches/main/protection \
  --input .github/branch-protection-main.json
python3 scripts/check-branch-protection.py
```

Statuschecks müssen nach dem Zusammenführen unter ihren neuen Namen verfügbar sein;
Schutz vor dem nächsten fachlichen Merge aktivieren und kontrollieren. Änderungen
an Workflows selbst gehören in ein geprüftes PR; ein Administrator könnte sonst
auch die Prüfdefinition entfernen. Das Gate ersetzt keine Zugriffskontrolle.

Referenzen: [GitHub Branch Protection](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches),
[REST Branch Protection](https://docs.github.com/en/rest/branches/branch-protection),
[Workflow Runs](https://docs.github.com/en/rest/actions/workflow-runs).
