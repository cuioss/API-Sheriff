envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=finding
created=2026-09-23T15:49:11Z

# Finding (handed off from `deployment-configurability`, closing): Sonar java:S3398 on UpstreamAssetSource.defaultSslContext()

`deployment-configurability` is closing with this one Sonar finding still undecided. Routing it to
`api-sheriff-0-2-0` since that epic continues touching this repository's code and can decide its
disposition (fold into a follow-up, resolve directly, or accept-and-close) rather than leaving it
unowned.

## What happened

Sonar finding `93017f` (rule `java:S3398`) flags
`api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/UpstreamAssetSource.java`'s private
`defaultSslContext()` method (currently at `:376-383`, using `SSLContext.getDefault()`).

Surfaced by PLAN-23 (`unit-lane-vacuity-audit`, PR #336) as a Residue note: that branch moved this
method's *call site* (confirmed in the PR diff, `UpstreamAssetSource.java` +/-81 lines) but did not
author the method itself, so it declined the fix as out-of-scope for that plan's declared work
(`git diff --stat` showed `UpstreamAssetSource.java` touched despite the PR body's "no production
behaviour changes" claim — reconciled at the time as a call-site relocation, not a behaviour change).

Re-verified at HEAD `070eda5` (2026-09-23): the method is unchanged since PLAN-23's landing —
`defaultSslContext()` still exists in its flagged form at the same lines.

## Disposition needed

One of: fold into a small follow-up fix in `api-sheriff-0-2-0`'s own work, resolve it directly
(a standalone one-method change), or accept-and-close with a recorded `// NOSONAR java:S3398`
rationale per this repo's Sonar compliance policy (`doc/development/sonar-quality-gate.adoc`) if the
finding is judged a false positive or deliberate idiom for this specific method.

## Source

`deployment-configurability` epic, standing item since the 2026-09-21 `cleanup` pass, never actioned
before the epic's close (PLAN-28/PLAN-29 both left it unowned — neither declared
`UpstreamAssetSource.java` in scope).
