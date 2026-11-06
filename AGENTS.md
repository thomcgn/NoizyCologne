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
2. After each coherent code change, run the relevant local tests and static analyses.
3. Fix project errors and warnings, including conversion, unchecked-conversion and null-type-safety diagnostics. Keep the strict compiler settings and rerun affected checks after corrections; do not add blanket suppressions.
4. During development and before local commits, use `scripts/check-quality.sh --quick` or suitable component checks (`--backend`, `--frontend`). The pre-commit hook runs QUICK. Full production builds and the entire test suite are not required after every small change or repeated immediately before a local commit.
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

When database tests are required, the gate creates its own disposable PostgreSQL
database; QUICK never starts a database container; selected database integration tests are explicitly deferred to STANDARD/FULL, while all current Java test sources remain compiled/analyzed.
Never point tests at the development or production database.
See `docs/QUALITY.md` for prerequisites,
Java/IDE coverage limits and hook installation. Configured JSpecify `@NullMarked` defaults
and explicit null annotations remain part of the mandatory analysis; this is not a complete
JSpecify verifier. `@NullUnmarked` resets are not fully modeled and new scopes require
separate compatible validation. Use explicit null checks at unannotated library boundaries;
do not invent non-null guarantees to make diagnostics disappear. Passing either or both
compiler versions does not establish live IDE warning freedom. Existing frontend-specific
`AGENTS.md` instructions remain in force.

## Keep routine work focused

- Read only the instructions and project sections relevant to the requested change; do not repeat a whole-project audit for each small task.
- Prefer small changes and reuse tested functions. Avoid unsolicited refactors, extra infrastructure and tests for unaffected behavior.
- Run targeted checks after coherent edits. After a correction, rerun failed or affected checks, not every completed check.
- The regular pre-commit QUICK hook is the commit check; do not manually run the same QUICK immediately beforehand. Do not repeat FULL for an unchanged commit. FULL remains mandatory in CI before integration or deployment.
- STANDARD (`--backend`/`--frontend`) covers its module, using safe incremental compilation. FULL performs clean Java compilation. Deletions/shared compiler inputs invalidate stale outputs in every mode.
- QUICK explicitly reports deferred database tests. It is not a complete integration certificate. Never invent success for a check that did not run.
- Record phase changes, checks and remaining limits in the task document. Timing targets guide scope, never abort required checks or justify unfinished work.

Tooling/negative compiler-fixture regressions run for changed tooling/rules and in FULL. Routine STANDARD module checks do not repeat unchanged gate fixtures. Strict compilation, fresh ECJ reports, module tests and STANDARD builds still run.
