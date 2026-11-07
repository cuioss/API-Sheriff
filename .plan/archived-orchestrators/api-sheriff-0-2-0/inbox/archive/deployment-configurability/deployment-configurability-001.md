envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=finding
created=2026-09-23T07:10:49Z

## Cross-epic finding: ADR-0050 ordinal collision

Reported by `deployment-configurability`'s `analyze` while reconciling PLAN-28's landing (PR #341,
merge `1994f28d2334fc4698d2352d47f3b72e6e3448ee`).

**What happened**: `deployment-configurability`'s PLAN-28 claimed ADR ordinal `0050`
(`0050-A_JWT_whose_type_the_engine_exposes_no_entry_point_for_is_verified_signature-only_and_its_claim_set_is_the_callers_authority.adoc`)
and landed first via PR #341. This epic's resume_anchor independently records that PR #343
(`feature/plan-16-application-portal`, merge `69b322b572c0734c7bc59c5b8d81a55f64539382`) — believed
here to be `api-sheriff-0-2-0`'s own PLAN-16 — also claimed `0050`
(`0050-Portal_templates_render_on_a_standalone_Qute_engine_and_escape-bypass_constructs_are_refused_at_boot.adoc`)
and landed second, the same day. `doc/adr/` now holds two files both numbered `0050`. Verified
directly: `ls doc/adr/ | grep -oE '^[0-9]{4}' | sort | uniq -d` returns exactly `0050` — the only
duplicate ordinal in the corpus.

**Why this epic, not deployment-configurability, is likely the one to fix it**: PLAN-28's PR merged
first (`1994f28` before `69b322b`), so `0050` was genuinely free when it was claimed. The later PR
(#343) did not re-check freshness against a concurrently-merging sibling epic's ADR before claiming
the same number. `deployment-configurability`'s queue is now fully terminal (nothing to launch a
follow-up from); this epic's queue has active work.

**Suggested remedy**: renumber the portal ADR
(`0050-Portal_templates_render_on_a_standalone_Qute_engine...`) to the next genuinely free ordinal,
`0053` (0001-0049 plus 0050/0051/0052 all now claimed), or file a small corrective follow-up if this
epic prefers to handle it that way. `deployment-configurability` is not staging a plan for this —
it belongs to whichever epic owns the portal work.
