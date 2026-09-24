envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:27Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): A documented permissive config rule had an undocumented security refusal, leading operators into a boot-time error

Original lesson `2026-09-23-07-009` (component `api-sheriff`).

## What happened

`doc/configuration.adoc` documented the substitution rule "a `${VAR}` written at a map-valued key
supplies the whole map". `ConfigLoader`'s substitution code in fact REFUSES whole-object substitution
at any object pointer covering a secret-classified field (a CWE-522 guard). An operator following the
documented rule at a secret-bearing map key would therefore hit a boot-time `ConfigError` the guide
never warned about, with no documented remedy (write a bare `${VAR}` at each secret field instead).

## Candidate rule

The general rule is stated in the guide; the exception is enforced only in code. Documentation of a
permissive rule is read as exhaustive, so every narrowing guard added later is a new gap in the doc
unless the same change updates it. Security-motivated refusals are the highest-risk subclass, because
they are added precisely where an operator is most likely to be doing something sensitive and least
likely to be expecting a hard failure. When production code adds a refusal path to a rule that
documentation states permissively, the same change must document the exception AND the remedy the
error itself gives — a configuration guide's bullet describing what is allowed is incomplete until it
names what is refused.

## Source

`deployment-configurability` PLAN-28 (PR #341), PR review comment `594592` (coderabbitai, resolution
`fixed`). Fixed: the map-valued-key bullet now states that whole-object substitution is refused when
the object contains a secret-classified field, and directs the operator to the per-field remedy.
