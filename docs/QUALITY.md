# Verbindliches Quality Gate

Die Root-`AGENTS.md` gilt für alle Codex-Aufträge im Monorepository, zusätzlich zu
Unterverzeichnis-Regeln. Nach zusammenhängenden Änderungen sind die betroffenen
Tests/Analysen auszuführen. **Vor jedem lokalen Commit muss `--full` erfolgreich
sein.** Fehler beheben, relevante Prüfungen wiederholen, Diff prüfen und erst dann
committen. Keine `--no-verify`-Umgehung, Regelabschwächung, pauschale Suppression oder
Entfernung fachlicher Funktionen zur „Reparatur“ eines Checks.

Verbindliches Ziel ist **null projektbezogene Fehler und Warnungen in VS Code vor
jedem Commit**, einschließlich bereits vorhandener Warnungen. Conversion-,
Unchecked-Conversion- und Null-Type-Safety-Diagnosen sind immer fachlich zu beheben.
Auch als `INFO` eingeordnete unsichere Konvertierungen gelten als blockierend;
ein Warnungsbestand darf nicht durch eine bloße Dokumentation freigegeben werden.

Codex lädt Repository-Anweisungen aus `AGENTS.md` entlang des Pfads vom Root zum
Arbeitsverzeichnis; spezifischere Dateien ergänzen beziehungsweise überschreiben
übergeordnete Regeln. Siehe die [offizielle AGENTS.md-Dokumentation](https://learn.chatgpt.com/docs/agent-configuration/agents-md).
Die technische Commit-Sperre übernimmt zusätzlich der lokal installierte Hook.

## Aufruf und Voraussetzungen

Vom Repository-Root, ohne besondere Umgebungsvariablen:

```sh
scripts/check-quality.sh --backend
scripts/check-quality.sh --frontend
scripts/check-quality.sh --full
```

Erforderlich: Bash, Git, Python 3, Java/JDK mindestens 21 und der ausführbare
Maven-Wrapper. Für Backend-Prüfungen muss Docker mit erreichbarem Daemon verfügbar
sein. Das PostgreSQL-17-Alpine-Image entspricht der vorhandenen Compose-Konfiguration.
Beim ersten Lauf benötigt Maven Netz-/Repository-Zugriff für Projektabhängigkeiten
und den fest versionierten Eclipse-Compiler `org.eclipse.jdt:ecj:3.46.100`.
Frontend: eine von der installierten Next.js-Version unterstützte Node.js-Version,
npm und einmalig `cd frontend && npm ci`. CI verwendet weiterhin Java 21/Node 24.
Es werden keine ungefragten Bibliotheksversionsupdates durchgeführt.

Fehlende Werkzeuge, Dependencies, ein nicht erreichbarer Docker-Daemon oder eine
nicht startfähige Testdatenbank führen zu einem Fehlerstatus. Das Skript installiert
keine Frontend-Abhängigkeiten automatisch. Es erzwingt `skipTests=false` und
`maven.test.skip=false`; die Prüfroutine hat keinen Skip-Schalter.

## Was läuft?

Alle Modi prüfen Arbeitsbaum **und Index** mit `git diff --check`, Konfliktmarker,
konfigurierte Secret-Muster und die Regressionstests der Prüfroutine. Shell-Syntax
von Prüfsystem, Hook und Installer wird kontrolliert.

Backend:

1. `backend/mvnw … clean test-compile`: javac, `-Xlint:all`, `failOnWarning=true`.
   Auch unveränderter Quellcode wird neu kompiliert, kein falscher Erfolg durch
   bereits vorhandene `.class`-Dateien.
2. Reproduzierbarer ECJ-Lauf für **main und test**, Java-21-Kompatibilität,
   separate Ausgabeordner, `-failOnWarning`, versionierte Einstellungen in
   `config/java-quality.prefs`. Erfasst zusätzlich unbenutzte Imports,
   annotierte Null-Zugriffe und weitere standardmäßig aktive ECJ-Prüfungen.
   Zusätzlich werden die erzeugten XML-Berichte unabhängig vom Compiler-Exitcode
   ausgewertet: **jeder Problem-Eintrag, einschließlich INFO, blockiert**. Fehlende,
   kaputte oder unvollständige Berichte gelten ebenfalls als Fehler; alte Berichte
   werden vor jedem Lauf gelöscht.
   JSpecify-`@NullMarked`-Defaults einschließlich der Bibliotheksverträge werden
   erkannt; unannotierte Bibliotheksgrenzen benötigen ausdrückliche Null-Prüfungen.
3. Echte negative Compiler-/JUnit-Fixtures in temporären Verzeichnissen.
4. `mvn verify` einschließlich aller regulären Tests und Paketierung gegen die
   ausschließlich für diesen Lauf gestartete PostgreSQL-Instanz.

Frontend:

1. Installiertes `next typegen`.
2. Installiertes `tsc --noEmit`.
3. `npm run lint -- --max-warnings=0`.
4. `npm test` (bestehende React/jsdom-Regressionen).
5. Echte TypeScript-/ESLint-Fehler-Fixtures außerhalb der getrackten Quelldateien.
6. `npm run build`.

Es folgt eine erneute Diff-/Index-/Repository-Prüfung. Ein fehlgeschlagener Schritt
beendet den Lauf mit Fehler; spätere Schritte gelten ausdrücklich **nicht** als
geprüft. Logs und temporäre Testmedien liegen in der ignorierten `.quality/run.*`.
Logs können HTTP-Testdaten enthalten und sollen vor einer Weitergabe geprüft werden.

### Datenbanksicherheit und optionale historische Diagnose

Das Skript übernimmt **keine vorhandene `DB_URL`**. Es startet einen eigenen
Container ohne vorhandene Volumes, bindet einen zufälligen Port ausschließlich an
127.0.0.1 und verwendet eigene Testzugangsdaten. Es wartet auf den finalen
TCP-Server (nicht den temporären initdb-Unix-Socket-Server). `DISCOVERY_ENABLED=false`
verhindert Scheduler-Abrufe; Testmedien gehen in den jeweiligen Logordner.
Container und seine anonymen Daten werden auch bei Fehler/Abbruch entfernt.
Die Entwicklung auf Port 5433 und Produktionsdaten bleiben unberührt.

Der vorhandene `ReadOnlyDiscoveryDiagnosisTests` ist ein **optionaler** Offline-Test
für einen privaten historischen Export. Ohne `NOIZY_DISCOVERY_SNAPSHOT` meldet das
Skript ausdrücklich, dass dieser Test nicht läuft; JUnit weist ihn als übersprungen
aus. Er ist keine Voraussetzung für neue Klone/CI und kein Ersatz für die regulären
Discovery-Fixtures. Ein bewusst gesetzter ungültiger Exportpfad führt zum Fehler.
Keine erforderliche Prüfung wird stillschweigend übersprungen.

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
übergangen werden. Ohne aktuellen IDE-Diagnosenachweis wird nicht committet.

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
der eigenständige IDE-Nachweis bleibt erforderlich.

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
sind idempotent. Die versionierte `.githooks/pre-commit` ruft immer `--full` auf,
manipuliert keine gestagten Dateien und führt weder Commit noch Push aus.

Bei Problemen: tatsächliche Fehlermeldung/Log untersuchen, fachlich korrigieren,
betroffene Checks wiederholen und vor dem Commit vollständig prüfen. Bei defekter
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

`.github/workflows/ci.yml` prüft jeden Branch-Push und Pull Requests nach `main`:
Projektstruktur, vollständiges Quality Gate und Caddy-Konfiguration. Java 21,
Node 24 und `npm ci` entsprechen dem Setup des Deployment-Workflows. Diese CI
veröffentlicht keine Images und führt kein Deployment aus.
Die vorhandene Caddyfile-Formatwarnung wurde mit dem offiziellen Formatter
bereinigt; die daraus adaptierte JSON-Konfiguration bleibt identisch.

`.github/workflows/ghcr.yml` ruft dieselbe vollständige Routine vor dem
Publish/Deploy auf. Dieser separate Workflow startet weiterhin bei Push auf
`main` beziehungsweise durch ausdrücklichen manuellen Aufruf. Publish/Deploy
bleiben vom erfolgreichen Verify-Job abhängig.
