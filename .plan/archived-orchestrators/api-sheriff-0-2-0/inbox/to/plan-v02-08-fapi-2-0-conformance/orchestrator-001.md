envelope_version=1
sender_type=orchestrator
sender_id=orchestrator
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-01T12:39:00Z

## ADR ordinal 0056 is now taken on main — renumber this plan's new record before pushing

**From the orchestrator, 2026-10-01, after PLAN-V02-19 landed as PR #367 (squash `6bb90765` on `main`).**

### What changed on main

PLAN-V02-19 renumbered the header-matcher decision record from the duplicate `0053` to **`0056`**:

- `doc/adr/0056-A_header_matchers_name_is_normalised_once_at_the_route-compile_seam_and_its_fields_compose_with_AND.adoc`

It also added `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/AdrOrdinalUniquenessContractTest.java`, which fails the build when two files in `doc/adr/` share a four-digit ordinal prefix.

### Why it concerns this plan

Observed in this plan's worktree at the time of writing:

- `doc/adr/0056-The_BFF_authenticates_with_private_key_jwt_pushes_every_authorization_request_and_binds_its_tokens_with_DPoP.adoc`

Once this branch is synced with `main`, `doc/adr/` holds two records with ordinal `0056`, and the new contract test goes red. The merge queue rebases no filename, so nothing resolves this automatically.

### What to do

- Re-derive the next free ordinal from `doc/adr/` **after** syncing with `origin/main`. At `6bb90765` the highest ordinal on `main` is `0056`, so the next free one is `0057`; confirm it on the branch rather than taking that number from this message.
- Rename this plan's record to that ordinal, update its `= ADR-NNNN:` title line, and repair every reference to it (other ADRs, `doc/`, Javadoc, the PR body).
- References that mean the header-matcher record use `ADR-0056`. References that mean the portal record keep `ADR-0053`.

### Also changed by the same landing

`UpstreamTimeoutException` moved out of the `UpstreamFetcher` interface. Its qualified name is now `UpstreamAssetSource.UpstreamTimeoutException` (was `UpstreamAssetSource.UpstreamFetcher.UpstreamTimeoutException`). Relevant only if this plan references it.
