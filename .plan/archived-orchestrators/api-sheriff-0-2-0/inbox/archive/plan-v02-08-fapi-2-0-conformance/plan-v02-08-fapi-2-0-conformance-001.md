envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-01T08:18:36Z

## Backlog request: align the cookie encryption key configuration with the file-based key model

**Requested by the operator on 2026-10-01**, during the outline of `plan-v02-08-fapi-2-0-conformance`, as a task for after that plan lands. It is explicitly out of scope for PLAN-V02-08 itself.

### What PLAN-V02-08 decided

The two signing keys the plan introduces (`private_key_jwt` client authentication and the DPoP proof key) follow one model:

- **Provided key:** a PEM (PKCS#8) file on a mount, referenced from `gateway.yaml` by path.
- **Omitted key:** the gateway assumes a single node and generates the key at startup.

### The inconsistency this leaves

The cookie-mode sealing key (`oidc.session.encryption_key`, `CookieKeyMaterial`) has the same two modes, but its provided mode takes a base64 AES-256 value from an environment variable through a bare `${ENV_VAR}` reference. After PLAN-V02-08 the gateway therefore supplies provided key material in two different ways: a mounted file for the signing keys, an environment-variable value for the cookie key.

### The requested work

Align the cookie encryption key configuration with the file-based model, so that a provided key is supplied the same way for all product-owned keys.

### Points the plan for it has to settle

- A symmetric AES-256 key has no PEM form. The file format for the cookie key (raw bytes, base64 text) is an open design question, not a mechanical port.
- This is a breaking configuration change to `oidc.session.encryption_key`; the secrets rule in `ConfigLoader`, the JSON schema, `doc/user/bff-cookie.adoc`, `doc/configuration.adoc` and the integration and compose descriptors all carry the current shape.
- The generated mode and its two documented caveats (sessions dropped on restart, key not shareable across replicas) are unaffected.
- It touches the `oidc` block, so it belongs in the same sequential chain as PLAN-V02-08, PLAN-V02-09 and PLAN-V02-12 and must not run concurrently with them.
- The ADR that PLAN-V02-08 writes for the signing-key model is the reference for the reasoning; whether its treatment of ADR-0011 (store paths kept out of `gateway.yaml`) carries over to the cookie key should be stated rather than assumed.

### Status of the source facts

The key model above reflects the operator's decisions as given to the outline revision of PLAN-V02-08, which was still running when this message was written. One sub-point was still awaiting the operator's confirmation at that time: whether a provided signing key must be accompanied by its public key. Read the landed plan and its ADR for the final shape before staging the follow-up.
