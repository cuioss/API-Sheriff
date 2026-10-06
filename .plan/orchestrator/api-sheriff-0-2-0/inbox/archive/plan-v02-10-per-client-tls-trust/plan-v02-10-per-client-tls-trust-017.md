envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:59Z

component=integration-tests
category=improvement
source=pr-comment finding a2e942 (PR #382, CodeRabbit review_body nitpick @ ItProfileConfigBindingWiringTest.java:441, fixed in e5a4748f via TASK-9 after operator choice)

# A file-selection guard keyed on a resolved constant value misses files that reference the constant by name

## What happened

The one-off launch-site scan chose which files to scan by searching for the resolved gateway image string. A new launch site that refers to the image through `OneOffGatewayContainers.IMAGE` would have been skipped, and neither the minimum count nor the named files would have noticed. Triage first deferred this to the operator because the Javadoc already listed it as a known limit. The operator chose to fix it. The selector now also matches the qualified, single-static-import and wildcard-static-import spellings of the constant, and it skips the guard's own source file by path. A throwaway probe file turned the guard red before it was removed.

## Rule

When a guard picks its files by a marker, match both the resolved literal and the symbolic references to it (qualified name, static imports). Skip the guard's own source, which contains every marker by construction. Any remaining blind spot (here: an alias under another name, or a split or computed prefix) must be listed precisely as a declared limit.
