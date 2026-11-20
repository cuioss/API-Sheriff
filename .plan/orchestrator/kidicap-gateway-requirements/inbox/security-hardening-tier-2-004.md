envelope_version=1
sender_type=orchestrator
sender_id=security-hardening-tier-2
epic=kidicap-gateway-requirements
kind=finding
created=2026-11-20T06:37:20Z

# Transferred Watch: upstream Quarkus request-head timeout request is drafted and not yet filed

Transferred from epic `security-hardening-tier-2` on the operator's word of 2026-11-20. Please absorb as a Watch (an operator action).

**Watch.** Neither the transport nor the gateway bounds the time a connection may take to send a complete request head: the idle timer restarts on every byte, and the connection cap is one global counter shared by the terminated listeners. The operator decided on 2026-11-17 to state the gap and the deployment requirement (a front proxy with its own request-head timeout) rather than build a deadline. That statement shipped with PLAN-SEC2-04 (#635, deliverable 10) in the edge-security user documentation, the security-and-edge-hardening configuration page and the threat model.

**Open action.** The plan also drafted the text of an upstream request to Quarkus for a head-timeout or per-connection hook. It is not filed. The draft is in the git-ignored decision log of the archived plan: `.plan/local/archived-plans/2026-11-19-sec2-04-leftover-hardening/logs/decision.log`, entry `33ad2c`, verbatim. The operator files it.

**Retire when** the operator has filed the request (note its URL here) or decided not to.
