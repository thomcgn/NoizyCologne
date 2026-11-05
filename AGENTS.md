# NoizyCologne repository instructions

Read `docs/PROJECT.md` and the instructions in the affected subdirectories before editing.
Keep existing local changes from other tasks intact. Do not push, deploy, or change production data without an explicit task authorizing it.

## Mandatory Quality Gate

These rules apply to every future Codex task, including features, fixes, refactoring,
migrations, APIs, components, tests and configuration changes:

1. Inspect the relevant repository state, instructions and current diagnostics before editing.
2. After each coherent code change, run the relevant local tests and static analyses.
3. Fix project errors and warnings; rerun the affected checks after corrections.
4. Before **every commit**, run `scripts/check-quality.sh --full` successfully on the final code. The configured pre-commit hook enforces this again.
5. Do not commit or report code complete after a failed or missing required check. Never silently skip checks or report an unexecuted check as passing.
6. Do not disable inspections, weaken warning rules, add blanket suppressions or remove working functionality to make checks pass.
7. Add appropriate tests for new behavior and regressions.
8. Preserve unrelated working-tree changes. Stage and commit only the task's changes; inspect the final diff and `git diff --check`.
9. Document actual environment failures and any remaining unrelated diagnostics, including their scope and cause. Do not hide an initial warning backlog.
10. End each task with a short quality summary: checks run, outcome, warnings/limitations and commit status.

The binding target is **no newly caused errors or warnings**. Clean up the initial
project warning backlog deliberately. `--backend` and `--frontend` are suitable
for intermediate work; only `--full` satisfies the pre-commit requirement.
Do not use `--no-verify`, alternate hook paths or skip flags to bypass the gate.

The gate always creates its own disposable PostgreSQL database. Never point tests
at the development or production database. See `docs/QUALITY.md` for prerequisites,
Java/IDE coverage limits and hook installation. New `@NullMarked` scopes require
additional JSpecify-aware validation; the compatibility profile alone is insufficient. Existing frontend-specific
`AGENTS.md` instructions remain in force.
