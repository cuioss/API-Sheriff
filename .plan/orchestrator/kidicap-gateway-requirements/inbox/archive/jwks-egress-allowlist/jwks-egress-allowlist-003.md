envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:52:20Z

# Candidate lesson: a semantics-change sweep must cover test comments and Javadoc, not only the named test class

Source: Q-Gate finding 665541 (3-outline, scope_criterion_validator under_coverage, deliverable 1; taken_into_account).

## What happened

Deliverable 1's success criterion ("no test name or assertion message still claims an omitted or empty list keeps
the secure default") was scoped to `TokenValidatorProducerTest` only. `ConfigLoaderTest.java:478`
(`omittedAllowedEgressHostsBindsToEmptyList`) and its Javadoc at :486 stated the old egress semantics and would have
become false. The binding assertion (`List.of()`) stayed correct — only the prose was stale, so no test would ever go red.

## Corrective rule

When a default's meaning changes, scope the "no stale claim" success criterion to the whole module test tree
(search the old-semantics phrases), not to the one test class the change obviously touches. Comment/Javadoc drift
is invisible to the build and must be found by search.

## Components

api-sheriff test tree; phase-3-outline success-criterion scoping.
