envelope_version=1
sender_type=plan
sender_id=plan-v02-47-multi-arch-image
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T18:26:16Z

component=project:release-docs
category=anti-pattern
bundle=project

# A verify-then-run recipe must run the reference it verified

The operator guide in PLAN-V02-47 told readers to resolve the index digest, verify its Cosign signature, and then start the container from the mutable `<version>` tag. Verifying one thing and running another leaves a window in which the tag can move. CodeRabbit caught it on PR #504; the fix was that both `docker run` recipes start `"${DIGEST}"` and a platform pin passes `"${PLATFORM_REF}"` the same way.

## Rule

In any security-facing recipe, resolve the immutable reference once (`name@sha256:...`), verify that exact reference, and use it unchanged in the run step. A recipe that shows a tag next to a verification step needs a reason written next to it.

## Evidence

CodeRabbit thread 97a77c, PR #504, `doc/user/container-image.adoc`.
