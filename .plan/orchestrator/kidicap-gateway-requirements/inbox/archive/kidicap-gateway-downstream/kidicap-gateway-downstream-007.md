envelope_version=1
sender_type=plan
sender_id=kidicap-gateway-downstream
epic=kidicap-gateway-requirements
kind=finding
created=2026-09-17T14:46:00Z

## Requirement: a login flood must not evict other browsers' in-flight logins

Filed by the downstream deployment `kidicap-gateway`. Code read at `main` (2026-09-17), not measured.
Downstream reference: `doc/autorisierung/identitaet-und-session.adoc#login-flut`, AU-9.

### Observation

* `oidc.login.path` is unauthenticated by design; every call creates a `PendingAuthorizationRecord`.
* Server mode uses `PendingAuthorizationStore.InMemory` with `DEFAULT_MAX_PENDING = 10_000`
  (`BffRuntimeProducer`). Beyond the bound the store evicts the **oldest** record
  (`evictOldestBeyondCapacity`) — a documented DoS guard against unbounded growth.
* The guard protects memory but hands the attacker a different effect: a loop of a few thousand
  `/auth/login` requests per record lifetime pushes out the records of real browsers that are at the IdP
  login form. Their callback then fails, and the failure looks like an IdP or cookie problem rather than
  load.
* `rate_limit` is reserved in the schema and has no effect, so there is no in-gateway counter-measure.

### Wanted (any of)

* Bind pending records to the browser-binding cookie so that one client cannot occupy more than a small
  number of slots, and evict within the same client first.
* A per-client-address or per-binding rate limit on the login-initiation and callback paths.
* At minimum, a log record and a metric when a live (unexpired) pending record is evicted, so the effect is
  diagnosable.

### Acceptance

A flood of `/auth/login` from one client does not make a concurrent login of another browser fail at the
callback.
