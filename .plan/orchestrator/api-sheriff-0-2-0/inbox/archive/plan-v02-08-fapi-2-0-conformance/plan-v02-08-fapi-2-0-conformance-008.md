envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-02T21:07:50Z

# Candidate lesson: module attribution does not resolve this project's Maven modules, so scoped gates silently become whole-tree gates

## Pattern

Several plan-marshall seams that are meant to narrow a build to the modules a change touches return "no module" for Java paths in this repository. Each seam degrades safely, by running the whole tree or by handing the build to the orchestrator, so nothing is left ungated. The cost is that every gate is the most expensive one, and the degradation is reported as a warning in the log rather than as a configuration defect to fix.

## What the record shows

- Pre-push quality gate: "Footprint paths matched a build_map glob but resolved to no bundle: 66 paths under api-sheriff/src/** and integration-tests/** (the bundle derivation resolves no Maven module here) ... The per-bundle sweep ran over zero bundles; both modules are covered by the whole-tree arms" (work log c9eb9b).
- Execute phase: "resolve-test-scope could not attribute the Java paths to a module (modules_resolvable false)", so the breakable-test slice was chosen by hand from the guards the outline names (decision log ce0f20). The step was reached through `pyproject_build resolve-test-scope` on a Maven project (work log 2d81db).
- The written-identifier diff of the module-testing profile is pytest-based and "does not apply to a Maven log"; Surefire reports were used as evidence instead (decision log 5ac27c).
- The build wrapper reported "unknown test(s) executed" for a Playwright run driven through Maven; the count had to be read from the Maven log and the JUnit XML (work log 32dae1).
- Consequence for wall time: module-tests and the quality gate for one module resolved to the orchestrator tier at 600 to 1000 s each, the whole-reactor gate at 640 s and the whole-reactor verify at 568 s, and they ran after every envelope (decision log a33793, 2d554d, a09756; work log 80ed4f).

## How to recognise it next time

- A warning that footprint paths "matched a build_map glob but resolved to no bundle".
- `modules_resolvable false` from the test-scope resolver on paths that plainly sit under one Maven module's `src/`.
- A per-bundle sweep reported as having run over zero bundles while the step still ends `done`.

## Suggested direction (for the orchestrator to classify)

- Tooling or project configuration: find out why the bundle derivation and the test-scope resolver return no module for `api-sheriff/src/**` and `integration-tests/**` although the architecture inventory lists both modules, and fix it at that seam. Whether the cause is the project's architecture data or the Maven extension's derivation is not established by this run.
- "Zero bundles derived for a non-empty buildable footprint" deserves a finding, not only a warning line: the gate still passes, so nobody is prompted to repair it.
- The test-scope and written-identifier helpers used on a Maven project should be the Maven ones; the record shows the Python ones being reached.
