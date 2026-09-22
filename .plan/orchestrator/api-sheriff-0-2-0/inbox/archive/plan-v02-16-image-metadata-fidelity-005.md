envelope_version=1
sender_type=plan
sender_id=plan-v02-16-image-metadata-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T17:51:36Z

component=integration-tests
category=bug
title=Three real defects in freshly authored verification code, all found by the review bot

# Three real defects in freshly authored verification code, all found by the review bot

## Observation

Every one of CodeRabbit's three actionable inline comments on PR #199 was valid and
all three were fixed on-branch. Notably, all three landed in the **verification
code the plan had just written** — the code whose entire purpose was to make the
image-metadata claim checkable. The plan's own gate (`verify -Ppre-commit` green,
Sonar new-code 0) did not surface any of them.

1. **A hard-coded list mirroring a set defined elsewhere.**
   `ImageLabelActivationWiringTest.LABEL_ARGS` duplicated the label-backed `ARG`
   set in `Dockerfile.native`. It was blind in the one direction that mattered:
   adding a label-backed ARG to the Dockerfile *without* the matching
   `docker-compose` `build.args` entry left the test green while the published
   image silently took the Dockerfile default — precisely the failure the test's
   own Javadoc says it exists to catch. Fixed by deriving the expected set from
   `Dockerfile.native` at run time, with a non-emptiness guard so a broken parse
   cannot pass vacuously.

2. **An unreachable timeout guard.** `ImageMetadataIT` called
   `in.readAllBytes()` before `process.waitFor(...)`. `readAllBytes()` blocks until
   EOF on the merged stream, and a hung `docker image inspect` never closes stdout,
   so `waitFor` was never reached and `INSPECT_TIMEOUT_SECONDS` was dead code.
   Fixed by reordering to waitFor → destroyForcibly-on-timeout → read; safe here
   because the `--format` output is a single label value, orders of magnitude below
   the 64 KiB pipe buffer that motivates the usual read-first idiom.

3. **A false justification on a skip.** A surefire/failsafe exclusion in
   `integration-tests/pom.xml` was justified with "no automated run builds the JFR
   image" — false: the `jfr` profile's `docker-build-jfr` execution builds exactly
   that image. The exclusion itself was correct for a different reason; the stated
   reason was not. Fixed by replacing the justification and adding the missing
   built-image assertion on the JFR image's version label.

## Rule

Newly authored *verification* code deserves the same scrutiny as the production
change it verifies — a green gate over a test that cannot fail is the most
expensive kind of green. Three recurring shapes to check before pushing:
a hard-coded list that must mirror a set defined elsewhere (derive it instead), a
timeout/guard that no execution path can reach, and a skip whose written
justification has not been re-verified against the current build files.
