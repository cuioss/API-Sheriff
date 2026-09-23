# PLAN-16: Restore browser-safe cookie posture, gate deliverability, close the ceiling footgun

epic: deployment-configurability
workstream: WS-04

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-09 against a RED `main`.** PLAN-05's landing (PR #282 → `b5369ca`) left
> `Demo Client E2E` failing, deliberately, pending this work. Deliverable 1 is what returns it to
> green. The three deliverables are PLAN-05's own D1/D2/D3, carried across from inbox message
> `bff-refresh-integration-coverage-007.md`.

## Objective

In cookie mode **the session IS the cookie**. PLAN-05 fixed a real bug — `CallbackEndpoint` never
stored the refresh token — and storing it grew the sealed session to **5123 bytes** against RFC
6265's ~4096-byte guarantee. Chromium drops the `Set-Cookie` silently, so the SPA stays anonymous
and all seven `[session-cookie]` tests fail from login onward while every `[session-server]` test
passes. The gateway never refused the seal, because the same change raised `max_cookie_size` to
`8192` on the cookie overlay — converting a loud fail-closed 500 (`ApiSheriff-114`) into a silent
browser-side drop.

This plan restores a browser-safe posture, then closes the two gaps that let a 5123-byte cookie ship
green: an IT suite that cannot see deliverability, and a validated ceiling above what browsers keep.

⛔ **The blindness predates PLAN-05 and must not be recorded as its regression alone.** The cookie sat
under 4096 only because the refresh token was not being stored — *the very bug PLAN-05 fixed was what
kept the suite accidentally honest*. Removing the accident exposed a gap that was always there.

## Deliverables

1. **Analyse why the cookie ITs PASSED while the cookie was being dropped — before changing them.**
   ⛔ **Added 2026-09-09 at operator direction; do not collapse it into deliverable 3's remedy.** The
   question is not why a test failed — it is why **nine tests reported success on a session that a
   real client could not hold**. A dropped cookie means no session; every one of those tests should
   have gone red the moment the payload crossed 4096. They did not. Produce a written analysis
   covering:
   - **Why each of the nine passed.** The working hypothesis is that the cookie was never dropped
     *in their world*: they drive **RestAssured, not a browser**, and RestAssured enforces no
     per-cookie limit — it stores and replays a 5 KB value without complaint, so the round trip is
     genuinely green for that client. ⛔ **Confirm that is the WHOLE mechanism, or find the rest.**
     Specifically settle whether anything else masked it — a fixture that re-authenticates between
     steps, a helper that re-seals rather than replaying, an assertion that reads the response body
     rather than the session state. A single confirmed explanation is worth more than a plausible one.
   - ⚠ **State plainly when the suite stopped being honest.** Before PLAN-05 the cookie sat under
     4096, so these tests were *correct*, not lucky-and-blind: they measured a round trip that really
     did work. They became blind at the exact moment the payload grew — the tests never changed. That
     boundary is what tells you whether this is a test defect or a missing invariant, and the answer
     shapes deliverable 3.
   - **What ELSE the same gap hides.** Deliverability is one browser-enforced property; enumerate the
     others the IT lane cannot see (`SameSite` handling, `__Host-` prefix rules, `Secure`-over-plain
     rejection, total per-domain cookie count and aggregate size) and state for each whether any test
     covers it and where. ⛔ **This is the deliverable's real value** — the size assertion fixes one
     symptom, and this asks whether the lane is blind to a class.
   - **Whether the same shape exists in other IT lanes.** `[session-server]` passed throughout; say
     why, and whether any other suite substitutes a permissive client for a real one.
   - **Where the boundary belongs.** `Demo Client E2E` is the only lane driving a real browser and it
     runs post-merge on `main` only. State what belongs in the IT lane versus what genuinely needs a
     browser, so deliverable 3 targets the right layer.
   ⚠ **Output is a document, not a test.** It is the input to deliverables 3 and 4 and the record
   that makes their scope defensible.
2. **Restore a browser-safe cookie posture — this is what returns `main` to green.** On
   `integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml`: set
   `refresh.enabled: false` and **remove** the `max_cookie_size: 8192` line so the browser-safe 4096
   default applies. Then remove the now-dead `oidc.session.max_cookie_size` row from
   `doc/development/declared-limit-assertion-coverage.adoc` (currently `PARTIAL`) **and re-check the
   counts that removal disturbs** — ⚠ that document has already produced two stale-count review
   comments this cycle.
   **Gate**: `verify -Pintegration-tests -pl integration-tests -am` stays at **130/130** *and*
   `Demo Client E2E` goes green.
3. **Make deliverability a PR-gated assertion, not a post-merge discovery.** Assert that the
   `Set-Cookie` value the gateway emits for a sealed session fits the 4096-byte browser budget.
   ⛔ **Assert on the EMITTED HEADER, never on a configured constant** — it must fail when the payload
   grows for any reason: a larger claim set, an extra token, a format change. A shared helper on the
   login path is likely right, since every cookie IT already logs in.
   ⛔ **The cheap alternative is insufficient and the reason is the whole point**: asserting in
   `SealedSessionCookieCodecTest` pins the codec against *its configured budget* — which is exactly
   the thing that was misconfigured. The assertion has to be against the browser limit.
4. **Close the `max_cookie_size` ceiling footgun — AND the guarantee `ApiSheriff-114` claims but does not provide.** The validator admits `40..8192`; browsers
   guarantee ~4096. Decide and implement either: cap the validated maximum at 4096, **or** keep the
   wider range behind an explicit acknowledgement plus a boot-time WARNING naming the risk. ✅
   `doc/user/bff-cookie.adoc` already carries correct guidance — extend it, do not restate it.

   ⛔ **The mismatch that makes this more than a footgun.** `doc/LogMessages.adoc:70` describes
   `ApiSheriff-114` as *"Logged when a sealed session cookie would exceed **the browser-safe ~4 KB
   budget**; the seal is refused **rather than emitting a value the browser would silently drop**."*
   The guard is real and fails closed — `SealedSessionCookieCodec`'s own Javadoc is accurate, saying
   *"larger than the **configured** budget … never a silent truncation"* — but it fires at the
   **configured** number, not the browser's. At `max_cookie_size: 8192` the gateway emits exactly the
   value that message promises it will refuse, logs nothing, and believes it succeeded. **That is the
   state `main` is in right now.**

   ✅ **This is a decision input, and it favours one branch.** Capping the validated maximum at 4096
   makes `ApiSheriff-114`'s existing text **true as written** — no doc edit, and no collision, since
   `doc/LogMessages.adoc` is declared by running PLAN-07. The acknowledgement-plus-WARNING branch
   leaves the message wrong and therefore **requires** a `doc/LogMessages.adoc` correction, which
   must then sequence behind PLAN-07. Weigh that cost explicitly rather than discovering it at
   implementation.

   ⚠ This is the **fourth** instance this epic has recorded of *a stated rule with no mechanism
   behind it* — after issue #269's inventory claiming a check it does not perform, the SNAPSHOT
   REMOVAL CONDITION carried as prose, and lesson `2026-09-02-22-002`'s comment naming its own guard.
5. **Audit EVERY integration test for pro-forma tests — ones that pass without exercising the
   behaviour they name, and therefore cannot fail correctly.** ⛔ **Added 2026-09-09 at operator
   direction.** The cookie ITs are one instance of a class: nine tests reported success on a session
   no real client could hold. This deliverable asks whether the suite has others.

   **What counts as pro-forma.** A test whose green says less than its name claims. Concretely, look
   for each of these and report per finding which one it is:
   - **A permissive substitute client** — the cookie case. The test drives something more tolerant
     than the real consumer (RestAssured for a browser, a raw client for a proxy), so a constraint the
     real consumer enforces is invisible.
   - **Asserting the request rather than the effect** — checking a 200 or a response body where the
     behaviour under test is a state change, a stored value, or a downstream call.
   - **An assertion that cannot fail** — a constant compared to itself, a `assertNotNull` on something
     just constructed, a try/catch that swallows, a loop over an empty collection.
   - **Pinned to a configured value rather than the real limit** — `SealedSessionCookieCodecTest` is
     the worked example: it pins the codec against *whatever budget is configured*, which is exactly
     the thing that was wrong.
   - **A precondition doing the work** — the fixture arranges the outcome the assertion then observes.

   **Method — apply the epic's own test.** For each suspect ask: *if the production behaviour were
   reverted, would this test go red?* That is the standing rule this repository already states for
   configuration keys (*a key that parses is not a key that acts*), applied to tests. A test that
   survives the reversion is pro-forma, whatever it asserts.

   ⛔ **Report findings; do NOT fix outside the cookie lane in this plan.** A whole-suite remediation
   is an unbounded surface and would drag a red-`main` fix behind it. Each finding outside
   `integration-tests/.../Bff*` gets a written entry — file, test, which class above, and what a real
   assertion would be — and is staged as follow-up work. Fixes inside the cookie lane may land here.

## Claim Labels

- OBSERVED: the overlay carries both offending settings — read at
  `integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml:171` (`max_cookie_size: 8192`)
  and `:172` (`refresh:`).
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: gateway.yaml no longer carries max_cookie_size:8192 - now explicitly keeps the shipped default value budget of 4019
- OBSERVED: the schema ceiling sits above the browser guarantee — read at
  `api-sheriff/src/main/resources/schema/gateway.schema.json:345-347`, whose description states
  *"valid range 40..8192, defaulting to 4096 when omitted"* and that **the configuration validator is
  the sole enforcing authority** (no `minimum`/`maximum`/`default` keyword is declared), so
  deliverable 3's change belongs in the validator, not the schema.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ConfigValidator.java still validates the range as 40..8192, deliverable 4 chose acknowledgement+WARN over capping
- OBSERVED: the root cause PLAN-05 fixed is documented in place — read at
  `CallbackEndpoint.java:233` (the comment naming the mechanism) and `:239`
  (`.refreshToken(result.refreshToken())`), with the guard itself at
  `TokenRefreshCoordinator.java:127`: `if (session.refreshToken() == null || !nearExpiry(session, now))`
  — **returning before any logging**, which is why the failure mode was silence and why an
  IdP-revoked session kept answering 200.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: CallbackEndpoint/TokenRefreshCoordinator refresh-token-storage root cause remains documented in place
- OBSERVED: `max_cookie_size` is documented in five files — `doc/configuration.adoc`,
  `doc/development/declared-limit-assertion-coverage.adoc`, `doc/user/README.adoc`,
  `doc/user/bff-cookie.adoc`, `doc/quality-report/documentation.adoc`. ⚠ Only the second is in this
  plan's declared surface; **`doc/configuration.adoc` is deliberately EXCLUDED — see Dependencies.**
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: max_cookie_size still documented across the same class of files
- OBSERVED: no cookie IT asserts the sealed value's size — re-verified at HEAD `481b05f`. ⛔ **Do NOT
  run the bare grep the source finding suggested**: `length|4096|budget` returns **2 hits in each**
  of `BffCookieSessionIT` and `BffCookieStatelessnessIT`, and **all of them are noise** — a Javadoc
  sentence about base64 padding (*"length is not a multiple of three…"*, `BffCookieSessionIT:278`)
  and an array index in a tamper test (`raw[raw.length - 1] ^= 0x01;`, `:289`). The source message's
  "zero hits" phrasing was imprecise; **the finding it names is correct and the search that
  establishes it is not.** Look for an assertion on the **emitted `Set-Cookie` value's size**, not
  for those tokens. An asserted **absence**, verified as a presence would be.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: BffKeycloakLoginFlow.java now carries a browser-safe/budget assertion on the emitted Set-Cookie header
- HYPOTHESIS: no cookie IT depends on `refresh` being enabled — confirm/refute at
  `BffCookieSessionIT:154`, which the source message reports as a comment mention with no assertion
  (verify-at-outline). ⛔ If any assertion does depend on it, deliverable 1 costs coverage and must be
  re-scoped rather than pushed through.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: sheriff-config-cookie overlay's refresh block is now explicitly OFF with a comment explaining why
- Verify-first clause: re-read the overlay and the validator at HEAD before scoping. `main` is red,
  so HEAD is moving under remediation pressure.

## Expected Surface

- OBSERVED: `integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml` — deliverable 1
- OBSERVED: `doc/development/declared-limit-assertion-coverage.adoc` — the dead row and its counts
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffCookieSessionIT.java` — the deliverable-3 assertion (verify-at-outline: exact class TBD between this and a shared helper)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — deliverable 4's cap or acknowledgement (verify-at-outline)
- HYPOTHESIS: `doc/user/bff-cookie.adoc` — extending the existing guidance (verify-at-outline)

⛔ **`doc/configuration.adoc` is deliberately NOT declared**, even though `max_cookie_size` appears
there. PLAN-07 is running and declares it; touching it would collide. If deliverable 4 turns out to
need a `doc/configuration.adoc` edit, **stop and sequence behind PLAN-07** rather than widening this
surface mid-flight.

⚠ The third entry names a class with an unresolved alternative. This epic has measured that exact
shape going wrong twice — resolve it to a named file at outline and correct the declaration in the
same act.

## Dependencies and Sequencing

- **Depends on: none.** ⛔ **This plan is the remediation for a RED `main` and outranks the rest of
  the queue.**
- ✅ **Surface-disjoint from running PLAN-07**, verified rather than assumed: PLAN-07 declares
  `tls/`, `application.properties`, `doc/LogMessages.adoc`, `doc/adr/`, `doc/configuration.adoc` and
  `doc/security-threat-model.adoc`; this plan declares none of them.
- ⛔ **DELIVERABLE 2 ALONE CLEARS THE RED, AND NOTHING MAY DELAY IT.** This spec now carries **five**
  deliverables, one short of the scope-bloat guard, and deliverable 5 is an open-ended audit whose
  true size is unknown until it starts. ⛔ **If deliverable 5 grows, SPLIT IT OUT into its own plan
  rather than carrying it — and split before it can hold up deliverable 2, not after.** The audit is
  valuable and not urgent; `main` is red and that is both. If deliverables 3 and 4 prove larger than scoped, ship 1 and 2
  and split the durable half — a green `main` should not wait on the ceiling decision.
- ⚠ **`Demo Client E2E` is the only lane that catches this**, and it runs post-merge on `main`, never
  on the PR. Deliverable 3 exists precisely to move that signal onto the PR; until it lands, this
  plan's own verification depends on a gate it cannot run before merging.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-16-cookie-deliverability-and-ceiling.md"
```
