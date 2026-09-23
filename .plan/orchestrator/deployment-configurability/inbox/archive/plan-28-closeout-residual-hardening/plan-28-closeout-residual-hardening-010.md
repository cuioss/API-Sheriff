envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:16:45Z

# Candidate lesson: a documented config rule had an undocumented refusal, so the guide led operators into a boot-time error

**Source signal**: PR #341 inline review comment `594592` (coderabbitai, resolution `fixed`)
**Component**: `doc/configuration.adoc`, `api-sheriff/src/main/java/.../config/load/ConfigLoader.java`

## What happened

`doc/configuration.adoc` documented the substitution rule "a `${VAR}` written at a map-valued key
supplies the whole map". `ConfigLoader.substituteChild` (lines 502-507, via `coversSecretPointer`)
**refuses** whole-object substitution at any object pointer covering a secret-classified field.

An operator following the documented rule at, for example, `oidc.session` therefore hits a
boot-time `ConfigError` that the guide never warned about — and the remedy (write a bare `${VAR}`
at each secret field instead) appeared nowhere in the documentation.

## Why this is candidate-lesson shaped

The general rule is stated in the guide; the **exception is enforced only in code**. Documentation
of a permissive rule is read as exhaustive, so every narrowing guard added later to the reader is a
new gap in the doc unless the same change adds it. Security-motivated refusals are the highest-risk
subclass, because they are added precisely where an operator is most likely to be doing something
sensitive and least likely to be expecting a hard failure.

The sibling PR comment from the same review found the identical shape in the *set-membership*
direction (a closed enum documented without a newly-added member), which suggests the recurring
class is "code narrowed or widened the rule, prose kept the old one".

## Candidate rule

When production code adds a refusal path to a rule that documentation states permissively, the same
change documents the exception **and the remedy the error itself gives**. A configuration guide's
bullet describing what is allowed is incomplete until it names what is refused.

## Disposition in this plan

Fixed by TASK-016 (commit `b4ba2bc`): the map-valued-key bullet now states that whole-object
substitution is refused when the object contains a secret-classified field, and directs the operator
to write a bare `${VAR}` at each secret field.
