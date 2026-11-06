# NoizyCologne repository instructions

Read `docs/PROJECT.md` and the instructions in the affected subdirectories before editing.
Keep existing local changes from other tasks intact. Do not push, deploy, or change production data without an explicit task authorizing it.

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
database; QUICK must not start Docker for pure unit tests or documentation changes.
Never point tests at the development or production database.
See `docs/QUALITY.md` for prerequisites,
Java/IDE coverage limits and hook installation. Configured JSpecify `@NullMarked` defaults
and explicit null annotations remain part of the mandatory analysis; this is not a complete
JSpecify verifier. `@NullUnmarked` resets are not fully modeled and new scopes require
separate compatible validation. Use explicit null checks at unannotated library boundaries;
do not invent non-null guarantees to make diagnostics disappear. Passing either or both
compiler versions does not establish live IDE warning freedom. Existing frontend-specific
`AGENTS.md` instructions remain in force.
