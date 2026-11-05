# Verbindliches Quality Gate

Die Root-`AGENTS.md` gilt für alle Codex-Aufträge im Monorepository, zusätzlich zu
Unterverzeichnis-Regeln. Nach zusammenhängenden Änderungen sind die betroffenen
Tests/Analysen auszuführen. **Vor jedem lokalen Commit muss `--full` erfolgreich
sein.** Fehler beheben, relevante Prüfungen wiederholen, Diff prüfen und erst dann
committen. Keine `--no-verify`-Umgehung, Regelabschwächung, pauschale Suppression oder
Entfernung fachlicher Funktionen zur „Reparatur“ eines Checks.

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

**Kein Nachweis „0 VS-Code-Probleme“:** ECJ 3.46.100 ist eine eigenständige,
fest versionierte Batch-Analyse; die lokal installierte Java-Erweiterung verwendet
einen anderen JDT-Snapshot (3.46.200). Ihre laufende Problems-Ansicht ist über diese
Shell nicht vollständig auslesbar. IDE-spezifische Erweiterungen, Projektmodell,
Annotation-Inferenz und Zusatz-Inspections können weitere Diagnosen erzeugen.

Das Profil erhält den im bestehenden Workspace konfigurierten
`org.springframework.lang.NonNullApi`-Default und ergänzt explizite
`org.jspecify.annotations.NonNull`/`Nullable`-Verträge sowie die älteren Spring-
Annotationen. **Es ist kein vollständiger JSpecify-/`@NullMarked`-Verifier.**
JSpecify-Package-Defaults, freie generische Typvariablen und externe Bibliotheks-
Metadaten sind gesondert in der IDE beziehungsweise mit einem weitergehenden,
kompatibel eingerichteten Analyseprofil zu prüfen. Neue `@NullMarked`-Scopes dürfen
nicht allein mit diesem Kompatibilitätsprofil freigegeben werden.

Im ursprünglichen bereinigten ECJ-Lauf blieben **98 Informationshinweise** (15 main, 83 test); mit Event Change Monitoring sind es derzeit **109 Informationshinweise** (19 main, 90 test) zur
Null-Annotation-Interpretation an Bibliotheks-/Generikgrenzen, etwa Jackson,
Spring Data und generischen Assertion-Rückgaben. Sie sind sichtbar im Analyse-Log,
keine unterdrückten Warnungen und kein Beweis für 98 Laufzeitfehler. Es wurden
keine Inspections deaktiviert und keine `@SuppressWarnings` ergänzt. Die Grenze
wird explizit dokumentiert, statt eine vollständige IDE-Freiheit vorzutäuschen.
Neue Errors/Warnings blockieren das Gate; konkrete zusätzliche IDE-Probleme müssen
wie in `AGENTS.md` beschrieben untersucht und dokumentiert werden.

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
simulieren isolierte Mini-Repositories die Werkzeug-Rückgabecodes, fehlende
Abhängigkeiten und den vollständigen Hook-Ablauf. Ein realer temporärer Git-Commit
wird bei Fehler blockiert und bei Erfolg erlaubt; der Index wird dabei nicht
verändert. Weitere Tests schützen vorhandene Hook-Konfigurationen und erkennen
einen nur im Index versteckten Token. Diese Simulationen verändern keine
Produktionsdaten und gelten nicht als Ersatz für die echten Projektprüfungen.

`.github/workflows/ghcr.yml` ruft nach dem unveränderten Java-/Node-Setup und `npm ci`
dieselbe vollständige Routine auf. Die zusätzliche vorhandene Caddy-Validierung
bleibt bestehen. Publish/Deploy bleiben vom erfolgreichen Verify-Job abhängig;
bei dieser Einrichtung werden weder Workflow noch Deployment ausgelöst.
