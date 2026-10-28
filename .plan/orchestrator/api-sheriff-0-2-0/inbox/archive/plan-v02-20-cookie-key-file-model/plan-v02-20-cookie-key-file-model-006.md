envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:34:17Z

# Candidate lesson: a pre-boot "same file" rule built on lexical path comparison does not converge in review

- Source: Q-Gate findings 3d53bb and 20794f (6-finalize, code-review, OidcClientRules.java), resolution accepted by operator decision (review closed by operator override after commits 4bef4d64 and 008f7f7f)
- Suggested component: project-local (config validation rules, OidcClientRules); plan-marshall:phase-6-finalize for the non-convergence pattern

## What happened

The new offline rule refusing the same file for the cookie sealing key and a signing key compared `normalize()`d paths. Review round 1 found that lexical `..` collapsing disagrees with the filesystem when a parent is a symlink; the first fix (Files.isSameFile when both files exist) left the absent-file fallback still collapsing `..`, which the next round flagged again. Each round produced a new edge case on the same predicate, and the operator closed the review after the second fix (absent-file case refuses only `..`-free equal paths; anything else is left to the boot key-id check).

## Corrective rule

For a "these two paths name the same file" check, decide up front which layer is authoritative: compare with Files.isSameFile when both exist, refuse lexically only what is provable without collapsing `..`, and leave everything else to the runtime check that actually reads the key material (here the boot key-id comparison). Do not grow lexical normalization to chase filesystem semantics. Record the division of labour in the ADR so reviewers can see where the edge cases are handled.
