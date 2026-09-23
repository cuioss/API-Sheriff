envelope_version=1
sender_type=plan
sender_id=cookie-deliverability-and-ceiling
epic=deployment-configurability
kind=finding
created=2026-09-09T17:30:15Z

# Pro-forma integration-test audit — out-of-lane findings

An exhaustive read of all 56 files under `integration-tests/src/test/**` at `497c592` (deliverable 5
of `cookie-deliverability-and-ceiling`) produced 20 flagged methods across 203 declared test methods.
The audit is **read-only**: it authored and edited no Java. The full classification, the reproducible
censuses and the recomputed backlog live in `doc/development/test-corpus-integrity.adoc`
§ "The Integration-Lane Pro-Forma Audit".

This message carries the findings **outside** the `integration-tests/.../Bff*` lane. The in-lane ones
(`BffCookieActivationWiringTest:110`, `:156`; `BffCookieSessionIT:176`; `BffSessionLoginIT:41`;
`BffUserInfoIT:50`) are recorded in the note and are deliberately not routed here.

Nothing below is urgent. Each is a green test that would stay green through a regression it is named
to catch, so the cost of leaving them is a guard that reports a guarantee it never evaluated.

## Test fixes (9 methods, 8 files)

All are `strengthen` unless marked. Each row names the observable the fix has to reach; none needs a
new fixture.

| Site | Shape | What is wrong | What the fix asserts |
|---|---|---|---|
| `ApiSheriffIntegrationIT.java:66` `benchmarkGatewayHealthTargetServesOverHttps` | (d) | **`delete`** — identical arrange to `quarkusHealthEndpoint:36`, which asserts strictly more. Its Javadoc concedes it cannot pin what its name claims. | Delete the method; **move its merge-queue rationale paragraph onto `quarkusHealthEndpoint`** — that reasoning is real and is the only thing lost with the method. |
| `BodyLimitActivationWiringTest.java:144` `containerOverrideExceedsTheNegativeCaseBody` | (d) | Compares the compose override against `71303168L`, a hand-copied duplicate of `LargeBodyIT:105`. The Javadoc claims the coupling it does not check. | Derive the bound from `LargeBodyIT`'s own constant instead of restating it. |
| `ConfigLoadedIntegrationIT.java:49` `unmountedPathDeniedByDefault` | (c) | A 404 for an unmounted path is produced by *any* route-table failure, including loading no routes at all. | Add the positive control the class Javadoc already names — a 200 on the mounted `/proxy/get`. `managementHealthReportsUp:69` is not it: readiness is `UP` with no routes assembled. |
| `ConfigLoadedIntegrationIT.java:59` `declaredAnchorNamespaceWithoutEndpointServesNothing` | (c) | Same missing control. | The one added positive leg fixes both rows. |
| `DirectoryAssetServingIT.java:91` `authenticatedAssetRejectsWithoutToken` | (d) | Named "rejected 401 **before any file is read**"; the ordering is asserted nowhere and cannot be observed directly from outside. | Assert it by contrast: a request for a **non-existent** file under `/secure-assets` must also answer 401 — source-resolution-first would answer 404. |
| `MetricsIT.java:58` `sheriffMetersAppearAndMoveAfterProxyTraffic` | (d) | Named "appear **and move**"; every assertion is a `contains` over the scrape body, so nothing moves. | Sum before, act, assert strictly greater — the shape its own sibling `securityEventsMeterAppearsAndMovesAfterRejection:90` already uses. |
| `MtlsHandshakeIT.java:87` `wrongCaClientCertRejected` | (c) | When `test.mtls.wrong.keystore` is unset the helper builds a context with no key manager, byte-identical to `noClientCertRejected:77`. The test then passes for its sibling's reason. | Assert the property resolves to a readable keystore before using it. |
| `PassthroughFaultIT.java:109` `midStreamResetSurfacesAsAbort` | (c) | `assertThrows(IOException.class, …)` is satisfied by an unmapped fault SNI, an unreachable Toxiproxy or a listener that is down; the class drives no toxic-free relay, so nothing establishes that the relay works at all. | Add the matched control (same SNI, toxic removed, handshake completes and reads normally), or narrow the expected exception. |
| `TlsEdgeActivationWiringTest.java:291` `failsafeWiresMtlsSystemProperties` | (d) | Asserts `pom.xml` **contains the element name** `<test.mtls.port>`. A value pointing at the wrong instance passes — the exact failure its own comment describes. | Assert the value against the port the compose file publishes, as `EgressVerifyActivationWiringTest` does for its pair. |
| `WsAdmissionActivationWiringTest.java:82` `overlayDeclaresAnExhaustibleAdmissionBudget` | (d) | Both caps are checked against `SEQUENTIAL_UPGRADES = 10` (`:78`), a hand-copied mirror of `WebSocketProxyIT.LOW_CAP_SEQUENTIAL_UPGRADES = 10` whose Javadoc claims the lockstep. Raising one alone silently disarms the exhaustion regression. | Derive the bound from `WebSocketProxyIT`'s constant. |

## Cross-cutting observations (no single fix)

### 1. The unguarded mirror — four of the fixes above are one defect

A value is defined in one file, restated in another, and the restating file's Javadoc *claims the
lockstep* while nothing compares the two. `BodyLimitActivationWiringTest`, `WsAdmissionActivationWiringTest`,
`TlsEdgeActivationWiringTest` and (in-lane) `BffCookieActivationWiringTest` all carry it.

The corpus already contains the fix twice — `BenchmarkRealmCredentialConsistencyTest` and
`DescriptorInventoryWiringTest` both derive their expectations from the file that defines them, and
both explain why. That is what makes these defects rather than accepted trade-offs.
`EgressVerifyActivationWiringTest:111-122` is the honest middle case: still an unguarded mirror, but
its Javadoc names all three files that must agree and states that none can check the others.

### 2. `LogMessagesCatalogueTest` does not reconcile against `doc/LogMessages.adoc`

Out of the audit's pool (`api-sheriff/src/test/**`), same shape. The catalogue test checks identifier
uniqueness and band membership only. Nothing compares the declared records against the note's rows,
so a row naming a message the code no longer emits — or a message that never gained a row — is
invisible to the build. Those rows are convention-maintained and unguarded.

### 3. The shape (e)-2 relaxed-TLS census was wrong in both directions

Certificate validation is disabled lane-wide, and the figure carried into this plan — "4 files" —
came from censusing the literal `useRelaxedHTTPSValidation`.

* **Too narrow.** The per-specification form is spelled `relaxedHTTPSValidation` with a lower-case
  `r`, so a case-sensitive census misses all five files that use it, and misses the two JDK-level
  mechanisms (`TrustAllManager`, `InsecureTrustManagerFactory`) entirely.
* **Too broad.** Three of the four files it *did* report match only in **prose** — `GetWithBodyIT:256`,
  `LargeBodyIT:117` and `WebSocketProxyIT:127` each name the method in a comment. The static form has
  exactly **one** call site in the whole lane, `BaseIntegrationTest:57`.

Measured surface at `497c592`: **14 files, 31 occurrences, four distinct forms**, plus the 27 suites
inheriting the lane-wide relaxation. Enumerated in the note. The relaxation itself is deliberate and
correct (self-signed `localhost` bundle); the finding is about the census, not the posture.

This is the note's own documented **search-form constraint** recurring, and the `assertNotNull`
prose-occurrence problem recurring. Both were already written down in that note, and both repeated.
The rule worth carrying: *a census is a claim about a regular expression, not about a concept.*

### 4. Two blind spots the taxonomy structurally cannot see

* **`HostSmuggleGuardIT:73`** asserts `assertTrue(response.path("method") != null, …)` — an
  `assertNotNull` no marker census can count. The published marker counts are therefore a **lower
  bound** on the vacuous-shape population. One occurrence found in 56 files is evidence that nothing
  looks for it, not that it is rare.
* **`GracefulShutdownIT`** exercises no shutdown. Every method is honestly named and honestly scoped,
  and the class Javadoc states the limit outright — so nothing is flagged, because the taxonomy
  classifies methods. A reader scanning class names for shutdown coverage would still conclude it
  exists.

### 5. A per-request assertion cannot observe cumulative exhaustion

`WebSocketProxyIT`'s class Javadoc records seven green tests that ran while the relay leaked its
admission permit on every connection. None was vacuous. Detecting it needed an assertion of a
different shape — made *after N operations*, and *about the gateway* rather than about any single
response. Worth carrying into future coverage arguments; no fix is owed.

## Documentation drift (already corrected, recorded for the ledger)

* `cookie-deliverability-blindness.adoc` cited `BffKeycloakLoginFlow.java:308, :323`; deliverable 3
  of this same plan moved those sites to `:336, :351`. **Corrected in place.**
* `DescriptorInventoryWiringTest`'s Javadoc says the declared-limit note enumerates "sixteen files —
  seven `gateway.yaml` documents"; it now enumerates seventeen across eight. **Not corrected** — it is
  prose the guard explicitly does not assert (`:56-59`), and it is Java, which this deliverable does
  not edit.

## Scope note for whoever drains this

The audit covered `integration-tests/src/test/**` exhaustively. The corpus-wide backlog in
`test-corpus-integrity.adoc` is now **29 files / 87 marker occurrences, entirely inside
`api-sheriff/src/test/**`** — five of those rows entered by corpus growth since the first pass, not by
any decision. The integration-lane half of that backlog is empty. Do not read the exhaustive
integration-lane pass as coverage of the 29 files nobody has opened.
