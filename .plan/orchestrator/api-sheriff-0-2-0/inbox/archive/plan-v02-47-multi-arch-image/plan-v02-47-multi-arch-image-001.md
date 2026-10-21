envelope_version=1
sender_type=plan
sender_id=plan-v02-47-multi-arch-image
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T18:26:07Z

component=project:release-docs
category=improvement
bundle=project

# Qualify user docs for a capability that starts with the next release

A documentation deliverable that describes a capability the pipeline will only start producing with the next release (here: the two-platform image index) reads as true of every existing tag unless each statement is qualified. In PLAN-V02-47 the first draft of the container-image and compose-sample pages said the published image is a two-platform index without noting that tags up to 0.2.4 are linux/amd64 only. Three separate findings caught the same class of gap: the two review findings on the container-image and compose-sample pages and a CodeRabbit thread on the digest recipe, which returns a single-image digest for an older tag so the platform-pinning extraction does not apply to it.

## Rule

When a plan changes what a release will publish, list the released versions that predate the change in the deliverable, and add a "since release X" qualifier at every sentence, recipe and decision table that names the new behaviour. Verify each recipe against an existing tag (`docker buildx imagetools inspect ghcr.io/cuioss/api-sheriff:<old-version>`) as well as the new shape.

## Evidence

Review findings aaaf3f and 509da3, CodeRabbit thread 13d3b9, PR #504, merge commit 8e218103.
