# PLAN-06: Forwarded-Trust Allow-List — Environment Configurability and Coverage

epic: deployment-configurability
workstream: WS-05

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

The proxy allow-list for forward handling is `forwarded.trusted_proxies` in `gateway.yaml` — the
mandatory CIDR set ADR-0003 requires before any `Forwarded` / `X-Forwarded-*` header is believed.
The operator's question was whether it is configurable by environment variable. **It is, but only
half-way, and the half that is missing is the half a container operator needs.**

`gateway.yaml` carries a real `${VAR}` / `${VAR:-default}` substitution engine, and the loader's walk
descends into arrays, so **each entry** of the list is env-substitutable. But `coerce` handles only
`string`, `boolean` and `integer`/`number` — there is **no array case** — so a single variable can
never expand into the list itself, and the schema's `"type": "array"` refuses the attempt at boot.
The **cardinality is baked into the file**: an operator can fill three declared slots from three
variables, but cannot go from three proxies to four, or to one, without editing the mounted YAML.
Nor can a slot be switched off — an empty substitution fails CIDR validation and an unset bare
reference fails the boot.

Settle what the operator contract should be, close the gap if closing it is the right call, and give
the mechanism the coverage a security-critical trust boundary owes — because today **no shipped
configuration declares the block at all** and **no test proves an entry is substitutable**.

## Deliverables

1. **Decide the contract, and record it.** Three shapes, each with a real cost: (a) accept
   per-slot-only substitution, document it precisely as the contract, and ship an example — cheapest,
   and honest, but leaves an operator editing YAML to add a proxy; (b) teach the loader a
   list-valued substitution for schema-declared `array`-of-`string` pointers (a separator-split on
   one `${VAR}`), which closes the gap but adds a typing rule to a security-relevant parser;
   (c) allow a slot to be *disabled* by an empty substitution, filtered before CIDR validation —
   narrower than (b), but it makes an empty string meaningful, which is its own hazard on a trust
   boundary. State which was chosen, why, and what the rejected options would have cost.
   ⛔ **Whatever lands is the precedent for every other list-valued `gateway.yaml` key** (`tls.alpn`,
   `tls.cipher_suites`, `tls.passthrough_sni`, `auth.required_scopes`). Decide it as a contract and
   name the blast radius; do not patch this one key in isolation.
2. **If (b) or (c): implement it in the loader, and refuse ambiguity loudly.** The engine's existing
   posture is that a bad placeholder **fails the boot** rather than silently resolving to something
   plausible — preserve exactly that. A list-valued substitution must not become a path by which an
   unset or malformed variable yields a *silently shorter* allow-list: a shorter allow-list fails
   closed for traffic but fails **open** for the operator's intent, because the gateway then quietly
   stops believing a proxy it was configured to believe. Decide and record which of those two the
   design prefers.
3. **Ship a shipped example that actually declares the block.** None of the six shipped `gateway.yaml`
   files declares `forwarded:` at all, so the epic's operator-facing sample demonstrates nothing about
   the gateway's proxy trust. Add a `forwarded` block to `deployment/compose-sample/`, written in the
   env-placeholder form the contract settles, with the compose file supplying the variable. This is
   the deliverable that makes the feature discoverable.
4. **Close the coverage gap.** Two distinct holes, both required:
   - **Substitution**: `ConfigLoaderTest` covers substituted *scalars* (a string, a boolean, an
     integer) but never an **array element**, and every `trusted_proxies` test uses a literal. Add the
     matched pair — an entry supplied by `${VAR}`, and the negative control for an unset/malformed one.
   - **Effect**: assert the substituted value actually reaches `TcpPeerGate` and changes the trust
     decision. ⛔ Per the project's own rule, *a configuration key that parses is not a configuration
     key that acts*: a test that only proves the YAML loads proves nothing about the allow-list.
5. **Document the contract in `doc/configuration.adoc` beside the existing `trusted_proxies` row,**
   and state the container story explicitly: what an operator sets, where, and what still requires a
   file edit. If (a) is chosen, say the cardinality limitation out loud rather than leaving an
   operator to discover it at boot — an undocumented half-capability reads as a bug.

## Claim Labels

- OBSERVED: the allow-list is a **gateway.yaml** key, not a Quarkus property — `ForwardedConfig` is
  the `forwarded` block record carrying `trustedProxies`, read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ForwardedConfig.java:42-49`, and
  the schema declares it at `/forwarded/trusted_proxies` as `{"type": "array", "items": {"type":
  "string"}}` and **`required`** within the block — read at
  `api-sheriff/src/main/resources/schema/gateway.schema.json:217-226`. There is no `QUARKUS_*`
  mapping and no `application.properties` entry for it.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ForwardedConfig.java still a plain record carrying trustedProxies; schema still requires trusted_proxies as array-of-string, no QUARKUS_* mapping
- OBSERVED: `gateway.yaml` **does** support environment substitution. `ConfigLoader` runs
  `EnvSecretResolver` over the parsed document *before* schema validation, supporting `${VAR}` and
  `${VAR:-default}` resolved against `System.getenv` — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/ConfigLoader.java:75-92` and
  `EnvSecretResolver.java:56-68`. So "not configurable by env" would be the wrong answer.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: EnvSecretResolver / VAR substitution still runs in ConfigLoader before schema validation
- OBSERVED: the substitution walk **descends into arrays**, so each *element* is substitutable —
  `ConfigLoader.substitute` handles `ArrayNode` by calling `substituteChild` per index at
  `ConfigLoader.java:425-431`. A `trusted_proxies: ["${PROXY_A}"]` entry therefore resolves.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ConfigLoader.substitute still descends into ArrayNode per index
- OBSERVED: **but a substituted scalar can never become an array.** `ConfigLoader.coerce` switches on
  the schema-declared type with cases for `string`, `boolean`, `integer`/`number` and a
  shape-inference default — **no `array` case** — read at `ConfigLoader.java:474-481`. So
  `trusted_proxies: "${TRUSTED_PROXIES}"` yields a `TextNode`, and schema validation then refuses it
  against `"type": "array"`. **The list's cardinality is fixed in the file.**
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: ConfigLoader now has an array coerce case (ARRAY_TYPE constant); comma-separated single-var substitution is tested and shipped
- OBSERVED: **a declared slot cannot be switched off.** `${PROXY_B:-}` resolves to the empty string,
  which `ConfigValidator.validateForwardedTrust` then rejects — `parseCidr` returns empty and the
  error is `"malformed trusted_proxies CIDR: "` — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java:1040-1053`.
  A bare `${PROXY_B}` that is unset raises `MissingVariableException` and fails the boot instead. So
  every declared slot must carry a valid CIDR at every boot.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ConfigValidator.java still rejects the malformed-CIDR-from-empty-string case at line 1509
- OBSERVED: **no shipped configuration declares the block at all.** All six shipped `gateway.yaml`
  files — `deployment/compose-sample/docker/sheriff-config/`, and the five under
  `integration-tests/src/main/docker/sheriff-config*/` — carry **no `forwarded:` key**, and a repo-wide
  grep finds `trusted_proxies` only in the schema, the validator, the model, the docs and
  `api-sheriff/src/test/resources/config/valid/gateway.yaml:24`. Asserted absence.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: deployment/compose-sample/docker/sheriff-config/gateway.yaml now declares a forwarded: block at line 58
- OBSERVED: the value reaches the trust decision through `GatewayEdgeRoute:297-305`, which reads
  `forwardedConfig.trustedProxies()` and builds `new TcpPeerGate(trustedProxies)`; `TcpPeerGate`
  parses the CIDR set once at boot (`forward/TcpPeerGate.java:29,45`). That is the seam deliverable 4's
  effect-test must reach.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: GatewayEdgeRoute.java still reads forwardedConfig.trustedProxies() and constructs new TcpPeerGate(trustedProxies)
- OBSERVED: the validator refuses a trust-all range outright and WARNs on a broad-but-not-total prefix
  (`ConfigValidator.java:1062-1075`, `ApiSheriff-102`). Any new mechanism must not become a way around
  that refusal.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ConfigValidator.java still refuses a trust-all range and WARNs on a broad-but-not-total prefix
- HYPOTHESIS: **array-element substitution is not test-proven.** `ConfigLoaderTest`'s substitution
  tests cover a schema-declared string (`:766`), boolean (`:820-840`) and integer (`:858`) — all
  scalars — and every `trusted_proxies` test (`:392-407`, `:831`) uses a literal. Confirm/refute by
  deleting the `ArrayNode` branch at `ConfigLoader.java:425-431` and re-running the module suite: if
  nothing goes red, the walk is unproven (verify-at-outline). This is the load-bearing claim for
  deliverable 4.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: ConfigLoaderTest.java now carries extensive array-element/list-valued substitution tests for trusted_proxies
- HYPOTHESIS: a separator-split list substitution (option b) can be typed safely, because the schema
  pins the destination to `array`-of-`string` at that pointer and `declaredScalarType` already reads
  the schema tree — confirm/refute at `ConfigLoader.declaredScalarType` and `coerce`
  (verify-at-outline). If refuted, option (b) is materially more invasive than it looks and the
  decision in deliverable 1 shifts toward (a) or (c).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed: list-valued substitution typed against the array/string schema pointer is exactly what shipped
- Verify-first clause: settle both hypotheses against the implementing source and a real boot — never
  against this spec's prose or the schema alone. ⛔ Do **not** widen the mechanism beyond
  `trusted_proxies` on the strength of the outline; the generalisation is a separate decision with its
  own blast radius, named in deliverable 1.

## Expected Surface

⛔ **CORRECTED 2026-09-03 against the realized footprint of `6ba8879` (PR #254), per
`analyze` Step 4 item 5.** The pre-landing declaration is kept verbatim below it: this spec is
terminal, but `corpus cross-check` still reads this section, so a declaration measured wrong must
not be left standing as the surface every future comparison sees.

**REALIZED (9 files, +845/-23, `git show --stat 6ba8879`):**

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/ConfigLoader.java`
- `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java`
- `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteTest.java` — ⛔ **UNDECLARED**; deliverable 2's effect test landed here, not in the declared `forward/`
- `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ComposeSampleForwardedTrustWiringTest.java` — ⛔ **UNDECLARED**
- `deployment/compose-sample/docker-compose.yml`
- `deployment/compose-sample/docker/sheriff-config/gateway.yaml`
- `doc/configuration.adoc`
- `.plan/project-architecture/_project.json` — mechanical, `architecture-refresh`
- `doc/quality-report/code-correctness.adoc` — mechanical, `architecture-refresh`

**NOT realized — all four HYPOTHESIS entries were refuted by non-realization:**
`EnvSecretResolver.java`, `ConfigValidator.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/forward/`, `doc/adr/`.
⚠ The last one is load-bearing: `doc/adr/` was NOT taken, so ordinal `0039` remains free for PLAN-10.

### Pre-landing declaration (kept for the record — measured wrong at `edge/`)

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/ConfigLoader.java` — the array walk and `coerce`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java` — the missing array-element substitution pair
- OBSERVED: `deployment/compose-sample/docker/sheriff-config/gateway.yaml` — the shipped example's `forwarded` block
- OBSERVED: `deployment/compose-sample/docker-compose.yml` — the variable that feeds it
- OBSERVED: `doc/configuration.adoc` — the contract, beside the existing `trusted_proxies` row (`:262`, `:1929`, `:2063`)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/EnvSecretResolver.java` — only if the contract needs a new placeholder form (verify-at-outline) — **REFUTED, not touched**
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — only if option (c) filters empties before CIDR validation (verify-at-outline) — **REFUTED, not touched**
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/forward/` — the effect test reaching `TcpPeerGate` (verify-at-outline: exact class TBD) — ⛔ **REFUTED IN LOCATION; landed at `edge/GatewayEdgeRouteTest.java`.** The `exact class TBD` hedge is where the gate's blindness entered
- HYPOTHESIS: `doc/adr/` — a mechanism ADR, only if option (b) lands (verify-at-outline) — **REFUTED, not touched**

## Dependencies and Sequencing

- Depends on: **PLAN-02 for sequencing only.** Both are "a deployment-varying value an operator must
  express without rebuilding", and both touch `doc/configuration.adoc` and the shipped `gateway.yaml`
  files. Sequence after PLAN-02 so the two read as one environment-configuration story. No logical
  dependency.
- Overlaps with: PLAN-02 on `doc/configuration.adoc` and the shipped `gateway.yaml` / compose-sample
  files. Surface-disjoint from PLAN-01, PLAN-03, PLAN-04 and PLAN-05.
- Adjacent to: the forwarded-header parsing and trust semantics of ADR-0003, and `TcpPeerGate`'s peer
  check. **Consumed, never modified** — this plan changes how the CIDR set is *supplied*, never what
  the gateway does once it has it.
- Adjacent to: the trust-all refusal and the broad-prefix WARN. Untouched, and explicitly must not be
  circumventable by the new mechanism.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-06-forwarded-trust-env-configurability.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
