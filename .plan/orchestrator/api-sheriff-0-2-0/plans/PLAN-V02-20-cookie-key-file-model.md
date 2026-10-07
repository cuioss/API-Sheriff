# PLAN-V02-20: Provide the Cookie Sealing Key the Way the Signing Keys Are Provided

epic: api-sheriff-0-2-0
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Source: an operator request made during the outline of `PLAN-V02-08`, held until that plan landed.

## Objective

After `PLAN-V02-08` (ADR-0058) the gateway supplies provided key material in two different ways.
The two signing keys — the `private_key_jwt` client-authentication key and the DPoP proof key — are
either **provided** as a PEM file on a mount, named by path in `gateway.yaml`, or **generated** at
startup when omitted. The cookie-mode sealing key (`oidc.session.encryption_key`,
`CookieKeyMaterial`) has the same two modes, but its provided mode takes a base64 AES-256 value from
an environment variable through a bare `${ENV_VAR}` reference.

Align the cookie sealing key with the file-based model, so that every product-owned key is provided
the same way. The generated mode and its two documented caveats — sessions are dropped on restart,
and the key cannot be shared across replicas — are unaffected.

## Deliverables

1. **Decide the file format for a symmetric key, and record it in an ADR.** This is the open design
   question, not a mechanical port: an AES-256 key has no PEM form. Candidates include raw bytes and
   base64 text; weigh what an operator can generate and inspect with standard tools, and how the file
   is read bounded. ADR-0058 is the reference for the reasoning — in particular state whether its
   treatment of ADR-0011 (trust material kept out of `gateway.yaml`) carries over to a symmetric key,
   rather than assuming it. Derive the ADR ordinal from `doc/adr/` on the branch at write time.

2. **Replace the provided mode of `oidc.session.encryption_key` with a key-file reference.** This is
   a breaking configuration change; the Pre-1.0 rules apply, so no compatibility path is kept. It
   reaches every carrier of the current shape:
   - `CookieKeyMaterial` (`bff/cookie/`) and its wiring in `BffRuntimeProducer`;
   - the secrets rule in `ConfigLoader`, which today lists `/oidc/session/encryption_key` among the
     values a defaulted placeholder must not materialise;
   - `ConfigValidator`, whose cookie-mode rule documents the omitted-key generate mode;
   - `schema/gateway.schema.json`;
   - the cookie-mode integration overlays (`sheriff-config-cookie`, `sheriff-config-cookie-refresh`)
     and their compose wiring;
   - `BffLogMessages` and `doc/LogMessages.adoc` for any changed message;
   - the further documents that describe the key: `doc/development/bff-cookie.adoc`,
     `doc/variants/03-bff-cookie.adoc`, `doc/fapi_next_steps.adoc`,
     `doc/quality-report/security-posture.adoc` and the diagram
     `doc/resources/diagrams/tls-key-material.svg`;
   - the test fixtures and tests that set the key: `api-sheriff/src/test/resources/config/cookieboot/gateway.yaml`,
     `CookieKeyMaterialTest`, `ConfigLoaderTest`, `ConfigValidatorTest`, `CookieModeBootTest` and the
     integration test `BffCookieActivationWiringTest`.

3. **Tests** for both modes: a provided key file is read and seals and unseals; a malformed,
   oversized or unreadable file fails the boot loudly; the generated mode is unchanged.

3a. **Two key-file checks left open by `PLAN-V02-08` (#377).** Decide whether the boot refuses a
    configuration in which the client-authentication key file and the DPoP proof key file name the
    same file — the published client key would then also be the DPoP proof key — and implement the
    answer in `ConfigValidator` with a test. Re-measure the sealed cookie size with the `cnf` claim
    DPoP adds, against the cookie-size budget, and record the headroom.

4. **Documentation** in the layers that describe the key today: `doc/user/bff-cookie.adoc` ("Key
   material"), `doc/configuration.adoc`, and `doc/user/environment-variable-overrides.adoc`.

## Claim Labels

- OBSERVED: the cookie sealing key's provided mode is a base64 value set through `session.encryption_key` — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/CookieKeyMaterial.java` § `Mode.PASSED` and `ENCRYPTION_KEY_FIELD`
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: CookieKeyMaterial ENCRYPTION_KEY_FIELD session.encryption_key; Mode.PASSED decodes base64 and refuses non-base64
- OBSERVED: the two signing keys are provided as a PEM file named by path, or generated at startup — read at `doc/adr/0058-The_BFF_pushes_every_authorization_request_and_binds_its_tokens_with_DPoP_and_authenticates_with_private_key_jwt_unless_a_client_secret_is_configured.adoc` § "A key is either provided or generated"
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ADR-0058 section 3: a provided key is a PEM file named by key_file; the two signing keys are client authentication and DPoP
- OBSERVED: `ConfigLoader` treats `/oidc/session/encryption_key` as a secret a defaulted placeholder must not materialise — read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/ConfigLoader.java` § the secrets list
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ConfigLoader.SECRET_POINTERS lists /oidc/client_secret and /oidc/session/encryption_key; a defaulted placeholder materialising it is refused
- OBSERVED: the current shape is carried by `gateway.schema.json`, two cookie-mode integration overlays, `doc/user/bff-cookie.adoc`, `doc/configuration.adoc` and `doc/user/environment-variable-overrides.adoc` — a search for `encryption_key` on `origin/main` at `cd383c2e`
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: all five named carriers contain encryption_key on origin/main; the list is not exhaustive (claim 4)
- HYPOTHESIS: no further carrier exists outside the listed files — confirm/refute at outline by a repository-wide search for `encryption_key` and `CookieKeyMaterial`, without a pathspec (verify-at-outline). Re-grounding refuted the first list; D2 and the Expected Surface now name every carrier a search at `1a20edad` returns
  - verdict: contradicted | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: a whole-repo search finds further carriers in doc/development, doc/variants, doc/fapi_next_steps, doc/quality-report, tls-key-material.svg, a test fixture and five tests; D2 and the surface re-scoped to name them
- Verify-first clause: confirm at outline whether ADR-0058's key-file reader can be shared for a symmetric key or must stay separate; a shared reader that admits a PEM block where raw key bytes are expected is a defect, not a simplification
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: procedural clause applicable: ADR-0058's reader is bff/client/ClientSigningKey (two-block PEM) while CookieKeyMaterial decodes base64 AES-256; sharing is undecided

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/CookieKeyMaterial.java` — D2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — D2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/load/ConfigLoader.java` — D2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — D2
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java` — D2
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — D2
- OBSERVED: `integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml`, `integration-tests/src/main/docker/sheriff-config-cookie-refresh/gateway.yaml`, `integration-tests/docker-compose.yml` — D2
- OBSERVED: `api-sheriff/src/test/**` — D3
- OBSERVED: `doc/user/bff-cookie.adoc`, `doc/user/environment-variable-overrides.adoc`, `doc/configuration.adoc`, `doc/LogMessages.adoc` — D4
- OBSERVED: `doc/development/bff-cookie.adoc`, `doc/variants/03-bff-cookie.adoc`, `doc/fapi_next_steps.adoc`, `doc/quality-report/security-posture.adoc`, `doc/resources/diagrams/tls-key-material.svg` — D2
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffCookieActivationWiringTest.java` — D2
- OBSERVED: `doc/adr/` — one new record — D1

## Dependencies and Sequencing

- Depends on: none now that `PLAN-V02-08` has landed.
- It touches the `oidc` block and `BffRuntimeProducer`, so it joins the sequential chain with
  `PLAN-V02-12` and `PLAN-V02-09`: never concurrent with either. Order among the three is decided at
  emit time.
- Not concurrent with `PLAN-V02-04` (ADR corpus audit), because D1 adds a record.
- `docker-compose*.yml` is a gate-requiring path, so D2 runs the full pre-commit process.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-20-cookie-key-file-model.md" plan_id=plan-v02-20-cookie-key-file-model
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
