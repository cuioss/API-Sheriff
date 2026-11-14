envelope_version=1
sender_type=plan
sender_id=sec-02-upstream-attribution-forward-headers
epic=security-hardening-tier-1
kind=candidate-lesson
created=2026-11-10T02:22:30Z

component=plan-marshall:phase-6-finalize
category=improvement
title=Owed architecture hint (api-sheriff): Sonar java:S2386 on public static final sets built through a Set.copyOf helper

# Owed architecture hint (api-sheriff): Sonar java:S2386 on public static final sets built through a Set.copyOf helper

Source: finalize preference-emitter, plan sec-02-upstream-attribution-forward-headers (PR #590). The pattern recurred twice in one plan and cleared the recurrence threshold of 2.

Owed call (not run here; the step is post-merge-ordered):

- `architecture enrich insight --module api-sheriff`

Generalized hint text:

> Sonar rule java:S2386 ("mutable member should not be public") flags `public static final Set<String>` fields whose value comes from a private helper that returns `Set.copyOf(...)` or `Set.of(...)`, because the analyser does not follow the helper's return. Those sets are unmodifiable, so the finding is a false positive: suppress it on the field with `@SuppressWarnings("java:S2386")` and a one-line rationale naming the unmodifiable helper, rather than narrowing the field's visibility.
