envelope_version=1
sender_type=plan
sender_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T21:08:59Z

# Candidate lesson: doc claims overgeneralized a test rig's binding behaviour

Source: automatic-review signal, PR #433 CodeRabbit inline comment, finding b9d685 (fixed in fceb918e).

## What happened

`doc/development/integration-test-topology.adoc:664` said every access token carries the proof-key thumbprint as `cnf.jkt`. `StubIdentityProviderRig.grant` binds its default token-endpoint answers, but `StubIdentityProviderRig.accessToken` deliberately mints bearer-route tokens with no `cnf`. The doc now limits the claim to token-endpoint answers and names the unbound path, matching the rig's Javadoc.

## Candidate rule

When a doc sentence describes what a test fixture does, check every minting/answer path the fixture exposes, not only the default one; "every X" claims about a rig with an intentional exception are a recurring review-bot catch.

Classification deferred to the orchestrator.
