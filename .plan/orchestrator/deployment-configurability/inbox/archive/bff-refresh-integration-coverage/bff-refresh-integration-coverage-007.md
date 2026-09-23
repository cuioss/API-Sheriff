envelope_version=1
sender_type=plan
sender_id=bff-refresh-integration-coverage
epic=deployment-configurability
kind=finding
created=2026-09-09T12:24:20Z

## main is RED from bff-refresh-integration-coverage — cookie-mode sessions exceed the browser cookie limit

Supersedes the clean-landing claim in `bff-refresh-integration-coverage-006` on this one
point; everything else in that landing stands. The landing was emitted before the
post-merge regression was known.

```landing-facts
schema=landing-facts/1
plan_id=bff-refresh-integration-coverage
supersedes=bff-refresh-integration-coverage-006
pr=#282
merge_state=merged
post_merge_state=main_red
failing_workflow=Demo Client E2E
first_failing_commit=b5369cae3957444d9e5c55fb79789b5e6c55fba4
failing_tests=7
failing_scope=session-cookie project only
followup=owed_not_started
```

`Demo Client E2E` was green on the five `main` commits before this merge and has failed on
every commit since, starting at `b5369ca` — still failing on `673e3a7` and `481b05f`, so it
is persistent, not transient. `Maven Build`, `Integration Tests` and `Scorecard` are green.
The failure is confined to the one lane that drives a real browser.

## What broke

Seeding the refresh token into the session — the fix that plan shipped — grows the
cookie-mode sealed session, because in cookie mode the session IS the cookie. Access + id +
refresh seal to **5123 bytes**. Browsers only guarantee ~4096 bytes per cookie (RFC 6265),
so Chromium drops the `Set-Cookie` silently: the SPA stays `anonymous` and all 7
`[session-cookie]` tests fail from login onward, while every `[session-server]` test passes.

The gateway did **not** refuse the seal. That plan also raised `max_cookie_size` to 8192 on
the cookie overlay to get the nine cookie-mode ITs green, which converted a loud fail-closed
500 (`ApiSheriff-114`) into a silent browser-side drop. Confirmed: no `ApiSheriff-114` in
the failing run's log.

## The finding worth carrying — the ITs are blind, not merely wrong

- **No cookie-mode IT asserts the sealed value's size.** Zero hits for `length` / `4096` /
  `budget` across `BffCookieSessionIT` and `BffCookieStatelessnessIT`.
- **They drive RestAssured, not a browser.** RestAssured has no per-cookie limit and will
  store and replay a 5 KB cookie without complaint.
- The only size test is the unit-level `SealedSessionCookieCodecTest`, which pins the codec
  against *whatever budget is configured* — not against the browser's real limit.

So the nine cookie ITs prove sealing, tamper-rejection, no-readable-token, statelessness and
peer-unsealing — all real — but are structurally blind to **deliverability**, the property
that decides whether cookie mode works at all. They passed green on a configuration no
browser can carry. The only gate that catches it runs post-merge on `main`, never on the PR.

**This blindness predates that plan.** The cookie previously sat under 4096 only because the
refresh token was not being stored — the very bug the plan fixed was also what kept the suite
accidentally honest. Removing the accident exposed the gap.

Third contributor: `oidc.session.max_cookie_size` validates up to **8192**, so the schema
admits a cookie no browser will keep. The ceiling sits above the browser's guarantee, which
is what made the config change look legitimate at review.

## Owed work — three deliverables

**D1 — Restore a browser-safe cookie posture (this is what returns `main` to green).**
On `integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml`: set
`refresh.enabled: false` and remove the `max_cookie_size: 8192` line so the browser-safe
4096 default applies. Verify no cookie IT depends on refresh — checked at time of writing:
only a comment in `BffCookieSessionIT:154` mentions it, no assertion does, so this costs no
coverage. Then remove the now-dead `oidc.session.max_cookie_size` row from
`doc/development/declared-limit-assertion-coverage.adoc` (it is currently `PARTIAL`, added by
that plan) and re-check the counts that row's removal disturbs — that document has already
produced two stale-count review comments this cycle.
Gate: `verify -Pintegration-tests -pl integration-tests -am` stays at 130/130 **and** the
`Demo Client E2E` workflow goes green.

**D2 — Make deliverability a PR-gated assertion, not a post-merge discovery.**
Add a size assertion to the cookie-mode ITs: the `Set-Cookie` value the gateway emits for a
sealed session must fit the browser-safe 4096-byte budget. Assert on the emitted header, not
on a configured constant, so it fails when the payload grows for any reason — a larger claim
set, an extra token, a format change. This is the control whose absence let a 5123-byte
cookie ship green. Consider whether the assertion belongs to every cookie IT or to one
dedicated test; a shared helper on the login path is likely right, since every cookie IT
already logs in.
Note the cheaper alternative and why it is insufficient: asserting at the unit level in
`SealedSessionCookieCodecTest` pins the codec against its configured budget, which is exactly
the thing that was misconfigured. The assertion has to be against the browser limit.

**D3 — Close the `max_cookie_size` ceiling footgun.**
The validator admits 40..8192; browsers guarantee ~4096. Decide and implement: cap the
validated maximum at 4096, or keep the wider range but require an explicit acknowledgement
that above-4096 is not browser-safe and emit a boot-time WARNING naming the risk. Whichever
is chosen, `doc/user/bff-cookie.adoc` already carries the correct guidance
("raising `max_cookie_size` above 4096 moves the risk into the browser… treat a raised budget
as viable for a controlled client, not as a general answer") — the code should enforce what
that prose already says. This is the deliverable that stops the same configuration being
reachable again.

## Sequencing

D1 alone returns `main` to green and is config-plus-docs only. D2 and D3 are the durable
half — without D2 the same class recurs silently, and without D3 it stays configurable. D1
should not wait on them.

The operator explicitly chose to route this as separate work rather than reopen
`bff-refresh-integration-coverage`, and accepted `main` staying red in the meantime.
