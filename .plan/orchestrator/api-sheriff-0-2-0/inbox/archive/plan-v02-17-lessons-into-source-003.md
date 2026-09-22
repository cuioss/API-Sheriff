envelope_version=1
sender_type=plan
sender_id=plan-v02-17-lessons-into-source
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T19:11:39Z

component=integration-tests
category=anti-pattern
title=A Maven goal-prefix invocation carries a hidden Central dependency and dies before the gate runs

# A Maven goal-prefix invocation carries a hidden Central dependency and dies before the gate runs

The new rewrite-report job in `.github/workflows/maven.yml` invoked the gate as `rewrite:run` —
the goal-prefix form. Resolving a goal prefix requires Maven to fetch the plugin group's
`maven-metadata.xml` from Maven Central **even when the plugin is fully declared in an active
profile**: the prefix-to-coordinates mapping lives only in that metadata, so the local
declaration cannot answer it.

Central answered `429 Too Many Requests`. The job failed with:

```
No plugin found for prefix 'rewrite'
```

having never executed the gate at all.

## Why this is worth a rule

The failure is doubly misleading:

1. **The error names the wrong cause.** "No plugin found for prefix" reads as a missing or
   misconfigured plugin declaration. The declaration was present and correct; the network was
   the failure.
2. **The gate reports failure without having run.** A rate-limited prefix lookup and a genuine
   rewrite finding are different events, and the job surfaces them the same way. Worse, in the
   inverse configuration a transient Central outage can make a mandatory gate silently not run.

The dependency is invisible at the call site. Nothing about `rewrite:run` in a workflow file
suggests it reaches the network before it reaches the profile.

## Corrective action

In CI, invoke a plugin by **full coordinates**, never by goal prefix:

```
org.openrewrite.maven:rewrite-maven-plugin:run
```

Full coordinates resolve the version from the profile's own `<plugin>` declaration and need no
prefix metadata, so the invocation has no Central round-trip before the gate executes.

Generalised: the goal-prefix form is a developer-console convenience. Any non-interactive
invocation — a CI job, a script, a generated command — should use full coordinates so that a
registry outage cannot masquerade as a configuration error or as a gate result.
