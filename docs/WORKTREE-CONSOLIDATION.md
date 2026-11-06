# Lokale Worktree-Konsolidierung – 06.11.2026

## Ausgangszustand und Sicherung

Hauptverzeichnis: `/home/tom/Projekte/NoizyCologne`.
Vorheriges `main`: `2d276d073804459cf6d8fc0590cc2a4e4f3e22f7`.
Backup-Branch: `backup/main-before-consolidation-20261106`.
Vollständige lokale Archive aller fünf Worktrees einschließlich ignorierter
Dateien, Indexkopien und binärer Diffs liegen geschützt außerhalb des Repositories:

`/home/tom/Projekte/NoizyCologne-consolidation-backup-20261106-131056`

28 im Index enthaltene, auf der Festplatte fehlende Uploads wurden bytegetreu
wiederhergestellt und aus dem Index genommen. `/backend/uploads/` ist lokal in
`.git/info/exclude` geschützt. Uploads werden nicht versioniert. Die vorhandene
Frontend-`.env.local` und lokale Backend-Einstellungen werden erhalten;
`protected-files-sha256.json` im Backup ermöglicht den abschließenden Abgleich.
Keine neue Worktree-Umgebungsdatei wird angelegt.

## Branch-Abhängigkeiten und Integration

`git worktree list`, Status aller Worktrees, Graph und Merge-Bases ergaben:

| Branch | Stand vor Konsolidierung | Behandlung |
| --- | --- | --- |
| `fix/discovery-assessment` | `c59e877` | Commit bereits Vorfahr von main |
| `chore/quality-gate-performance` | `4adc5a1` | Vorfahr von Crawler und Multi-Source |
| `feat/javascript-location-crawler` | `b985e68` | Vorfahr von Multi-Source |
| `feat/multi-source-discovery` | `a91fd64` | Enthält alle sieben neuen Commits seit main |

Die Zusammenführung ist ein Fast-forward; bestehende Commits bleiben erhalten.
Es entstehen keine textuellen Merge-Konflikte und keine doppelten Cherry-picks.
Der gemeinsame Stand wird vor der lokalen main-Aktualisierung im Hauptverzeichnis
auf `chore/local-consolidation` geprüft.

## Uncommittete Discovery-Arbeit

Der Discovery-Worktree enthielt zwölf geänderte und fünf neue Dateien. Sein
unveränderter Originalstand ist zusätzlich über
`backup/discovery-uncommitted-20261106` (Stash-Snapshot einschließlich ungetrackter
Dateien) gesichert. Ein Dreiwegevergleich mit dem ursprünglichen Branch-Stand
und anschließend die fachliche Prüfung der Unterschiede zeigen:

- Store, Monitoring, Extraktion, Import, Programmtext und bestehende Backendtests
  enthalten die lokalen Änderungen bereits.
- Lifecycle und Lifecycle-Tests sind identisch enthalten.
- Matcher verwendet die weiterentwickelte Version `party-rules-4` und explizite
  Nullprüfungen; PartySeriesRecognition erhält strengere Nullverträge.
- EventVerification unterscheidet sich nur im deutschen Hinweistext.
- Der Scheduler verwendet den späteren DiscoveryBatchMerger mit Quellenbelegen,
  Konflikterkennung und begrenzten konfigurierten Fallbackquellen. Die ältere
  lokale Batch-Schleife wird nicht über die neuere Implementierung geschrieben.
- CandidateProcessor enthält die lokalen Änderungen plus den späteren Schutz
  kompatibler Fallbacks und widersprüchlicher Kandidaten.
- Prüfdialog und Frontendtests enthalten die ursprünglichen Bewertungsfunktionen
  plus Quellenvergleich und ausdrückliche Portfoliofreigabe.

Die ungetrackte Datei `tatus` im Multi-Source-Worktree wird im Originalarchiv
aufbewahrt. Sie gehört nicht zum Anwendungscode.

## Abnahme und Abschlussnachweis

Die Commitprüfung erfolgt über den unveränderten QUICK-Hook; für die Integration
wird zusätzlich `scripts/check-quality.sh --full` am endgültigen Commit ausgeführt.
FULL umfasst Java-Kompilierung/ECJ, Backendtests mit Wegwerf-PostgreSQL,
TypeScript, ESLint, Frontendtests und Produktionsbuild sowie isolierte
Browser-/Discovery-Fixtures. Der optionale historische Offline-Test erfordert
einen privaten Export und wird ohne diesen ausdrücklich als nicht ausgeführt gemeldet.

Startprüfungen erfolgen im Hauptverzeichnis mit abgeschalteten Schedulern und
eigener Wegwerf-Datenbank; bestehende Entwicklungs- und Produktionsdatenbanken
werden nicht migriert. Konfiguration und Uploads werden abschließend per SHA-256
abgeglichen. Worktrees werden erst nach erfolgreicher Prüfung und Sicherung mit
`git worktree remove` ohne Force entfernt. Redundante integrierte Branches werden
mit `git branch -d` entfernt; die Sicherungsbranches bleiben erhalten.

Der tatsächliche Abschlussstatus, genaue Testzahlen, Logpfade, Startprüfungen,
verbleibende Grenzen und die endgültige SHA werden nach der Prüfung in
`CONSOLIDATION-RESULT.md` im oben genannten Backupverzeichnis protokolliert.
Die laufenden Gate-Protokolle liegen zusätzlich unter `.quality/run.*/`.
So bleibt der abschließende geprüfte Commit unverändert.

Es erfolgt ausschließlich eine lokale Konsolidierung gemäß ausdrücklichem
Auftrag. Kein Push, Deployment oder GitHub-Konfigurationswechsel. Eine lokale
FULL-Prüfung ersetzt keine GitHub-Actions-Freigabe: Vor späterer Veröffentlichung
bleibt der erfolgreiche verpflichtende CI-Check für die exakte SHA erforderlich.
Die Live-Problems-Ansicht von VS Code ist nicht angebunden; CLI-Prüfungen werden
nicht als IDE-Nachweis ausgegeben.
