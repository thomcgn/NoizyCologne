# NoizyCologne repository instructions

Read `docs/PROJECT.md` and the instructions in the affected subdirectories before editing.
Keep existing local changes from other tasks intact. Do not push, deploy, or change production data without an explicit task authorizing it.

## Mandatory Quality Gate

These rules apply to every future Codex task, including features, fixes, refactoring,
migrations, APIs, components, tests and configuration changes:

1. Inspect the relevant repository state, instructions and current diagnostics before editing.
2. After each coherent code change, run the relevant local tests and static analyses.
3. Fix **all** project errors and warnings, including existing conversion, unchecked-conversion and null-type-safety diagnostics; rerun the affected checks after corrections. Before every commit, VS Code must show **zero project errors and warnings**. Do not accept a warning backlog.
4. Before **every commit**, run `scripts/check-quality.sh --full` successfully on the final code. The configured pre-commit hook enforces this again.
5. Do not commit or report code complete after a failed or missing required check. Never silently skip checks or report an unexecuted check as passing.
6. Do not disable inspections, weaken warning rules, add blanket suppressions or remove working functionality to make checks pass.
7. Add appropriate tests for new behavior and regressions.
8. Preserve unrelated working-tree changes. Stage and commit only the task's changes; inspect the final diff and `git diff --check`.
9. Document actual environment failures and the scope of diagnostic verification. Never claim a live VS Code Problems check from CLI results alone. Without a current IDE diagnostic verification, do not commit. Unavailable IDE access does not excuse known warnings or permit their suppression.
10. End each task with a short quality summary: checks run, outcome, warnings/limitations and commit status.

The binding target is **zero project errors and warnings before every commit**, not merely no new warnings. Conversion and null-type-safety diagnostics must always be corrected, including ECJ reports categorized as `INFO`; the gate rejects every reported compiler problem.
`--backend` and `--frontend` are suitable
for intermediate work; only `--full` satisfies the pre-commit requirement.
Do not use `--no-verify`, alternate hook paths or skip flags to bypass the gate.

The gate always creates its own disposable PostgreSQL database. Never point tests
at the development or production database. See `docs/QUALITY.md` for prerequisites,
Java/IDE coverage limits and hook installation. Configured JSpecify `@NullMarked` defaults
and explicit null annotations remain part of the mandatory analysis; this is not a complete
JSpecify verifier. `@NullUnmarked` resets are not fully modeled and new scopes require
separate compatible validation. Use explicit null checks at unannotated library boundaries;
do not invent non-null guarantees to make diagnostics disappear. Passing either or both
compiler versions does not establish live IDE warning freedom. Existing frontend-specific
`AGENTS.md` instructions remain in force.
