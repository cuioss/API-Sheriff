envelope_version=1
sender_type=orchestrator
sender_id=release-lane-hardening
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-22T10:46:50Z

# Finding: watch the first release after PR #509 for the new join order

PR #509 (merge commit `00293178`) changed the order of the image join in `.github/workflows/release.yml`.
The join now pushes the index under a candidate tag, `leg-<release-tag sha>-index`, asserts, pulls,
signs and verifies it there, and only then points `<version>` and `sha-<release-tag sha>` at the
signed index digest and reads both back. Dry runs from a branch and from `main` have exercised every
step (runs 39732222258, 39751125940, 39759377284), but a dry run promotes to `dryrun-*-promoted`, not
to the real release tags, and has never run against a Maven Central release. The first real release
after this merge is the first run of the new order against `<version>` and `sha-*`.

This is also the first release to publish the two-platform index: tags up to 0.2.4 are linux/amd64
only.

## What to watch

When the next release run happens, the orchestrator should confirm, from the run and the package:

1. **Step order in the join.** `Assemble the index under its candidate tag`, the candidate-tag
   assertion and the amd64 pull check, `Sign the release index and both platform digests (keyless)`
   and `Verify all three signatures under the published identity` all succeed before
   `Promote the verified index to its final tags` and `Assert every final tag names the signed
   index`.
2. **Tags.** `<version>` and `sha-<release-tag sha>` resolve to the same digest, that digest is an
   index listing exactly linux/amd64 and linux/arm64, and it is the digest the sign step signed.
   `docker buildx imagetools inspect ghcr.io/cuioss/api-sheriff:<version>` is enough.
3. **Signatures.** The operator verification command from `doc/user/container-image.adoc` verifies
   the index digest and both platform digests.
4. **Package listing.** The new `leg-<commit>-index` tag is present next to the two
   `leg-<commit>-<arch>` tags and is not counted as a release tag in the release skill's "exactly
   one of each" verification.
5. **Dry-run job.** `cleanup-dry-run` is skipped on the release.

## If it goes wrong

- A failure before `Promote the verified index to its final tags` leaves no release tag by design;
  re-run the join alone (see the release skill, "If the image lane fails after the Maven release").
- A failure in the promote or read-back step is the one case that can leave a release tag behind; it
  names the signed digest, or the read-back says it does not. File the run link here and re-run the
  join.
- If the promotion changes the index digest (the read-back reports a tag that does not resolve to the
  signed index), that is a real defect in the new flow: stop, and treat the release as partial.

## Disposition needed

Close this finding when one real release has been observed against the checklist above, or amend it
with what was seen.
