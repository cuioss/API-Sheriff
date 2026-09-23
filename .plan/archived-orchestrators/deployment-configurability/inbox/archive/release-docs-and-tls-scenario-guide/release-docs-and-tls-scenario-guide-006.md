envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:23:02Z

component=documentation
category=anti-pattern
source_finding=pr-comment d44822 (CodeRabbit, fixed in-run; operator chose to drop the flag)

# Spec mandated -Dsurefire.failIfNoSpecifiedTests=false where it only hides a misspelled selector

## What happened

The plan spec required the targeted-test example (`test -pl api-sheriff -am -Dtest=ConfigLoaderTest`) in AGENTS.md and CLAUDE.md to carry `-Dsurefire.failIfNoSpecifiedTests=false`. Under `-pl api-sheriff -am` the only other reactor module is the pom-packaged root, which binds no surefire execution, so the override prevents no failure — it only lets a misspelled or deleted test name pass with zero tests run. The implementation followed the spec verbatim; CodeRabbit flagged it and the operator dropped the flag, adding a note that it is needed only when the `-pl` target pulls in upstream modules with their own tests (e.g. integration-tests depending on api-sheriff).

## Corrective action

Before writing a spec-mandated build flag into operator-facing command examples, check it against the example's actual reactor: a flag that suppresses a failure mode must name a module in that reactor that would trigger it. Default to the fail-closed surefire behaviour and document the override conditionally.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305)
- Finding hash: d44822, thread PRRT_kwDOPatrT86il0gM
- Ties to the CLAUDE.md principle "A successful build is not evidence that work happened"
