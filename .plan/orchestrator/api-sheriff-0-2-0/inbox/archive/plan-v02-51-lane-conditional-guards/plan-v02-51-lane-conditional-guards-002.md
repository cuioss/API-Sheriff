envelope_version=1
sender_type=plan
sender_id=plan-v02-51-lane-conditional-guards
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-30T00:33:01Z

# Candidate lesson: write source-parsing guard tests as narrow allowlists from the first draft

**Component:** project test practice (integration-tests / api-sheriff contract guard tests); possibly plan-marshall:persona-module-tester
**Category (suggested):** improvement

## What happened

Three guard tests parse build or config sources with hand-written readers: `MtlsLaneSelectionContractTest` (failsafe lanes in POMs), `BenchmarkScriptConfigContractTest` (k6 script routes) and `LogMessagesCatalogueTest` (LogRecord catalogues). In each one, the first draft tried to understand the full input language (Surefire include syntax, Maven layer inheritance, JUnit tag expressions, JS call forms). Any form it could not read fell through as "not matching".

Each review or bot round then found another input form that the reader silently misread. Every one needed its own fix task with red and green specimens:

- Code-review round 1 (6 fixes, TASK-9..14): resolver-call quoting, route-table scope, body-cap scope, allowed_methods scope, groups as tag expressions, inherited/constant @Tag.
- Round 2 (4 fixes, TASK-15..18): Surefire class-name patterns, root-pom layers, `${...}` property references, baseUrl()/literal URLs.
- Round 3 (1 fix, TASK-19): endpoint scopes.
- PR/bot round (TASK-20..23): a JDK glob mismatch on `**/dir/**` forms, unknown holder, management-port mapping, octal identifier literals.

TASK-15 and TASK-20 finally fixed the include-pattern problem the same way: they replaced a deny-list with an allowlist. Only `**/` plus one `.java` file name with `*` and `?` is evaluated, and every other form is refused with the lane and pattern named. After that the class of finding stopped. The advisories that still repeat at every head (uncovered refusal branches, any()/none(), reference depth) belong to the same broad-reader surface.

## Proposed rule

When a guard test reads a source language (POM, glob, script, Java source), it should state the narrow input shape it accepts. It should accept only the forms the real tree uses and that it can evaluate exactly. It should refuse every other form loudly, naming the location, rather than evaluate it partially. Each refusal branch should come with a specimen. A refusal that is ever hit is cheap to widen later. A reader that half-understands a wider language costs one review round per misread form, and while it is wrong it fails open.

## Evidence

Q-Gate code-review findings 67570a, b45f50, 8daced, ea7b74, 723297, cf44ee, 5b9e5f, a9fc78, 101be8, 236b6d and 8abb65. Decision log TASK-15 GREEN (543124: deny-list to EVALUABLE_PATTERN allowlist) and TASK-20 (bb2520/f565a6). There were four code-review loop-backs (14:37, 17:00, 18:40 and 20:49 via automatic-review/sonar).
