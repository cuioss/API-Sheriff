envelope_version=1
sender_type=plan
sender_id=plan-v02-03-documentation-restructure
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T02:00:40Z

component=plan-marshall:build-maven
category=bug
title=Learned timeout kills a full verify mid-run, and the dead JVM's port 8081 fails the NEXT run

# Learned timeout kills a full `verify` mid-run, and the dead JVM's port 8081 fails the NEXT run

Two coupled failures, one root cause.

## Failure 1 — the learned timeout under-provisions

The build-maven wrapper's **learned** timeout budgeted **358s** for a full `verify` whose real
duration is **~400s**. The build was killed mid-run. The kill surfaces as a build failure, not as a
timeout, so the first reading is "the branch broke the build".

## Failure 2 — the kill leaks a port, and the leak lands on the NEXT run

The killed JVM held **port 8081**. The next, otherwise-unrelated build failed in
`ManagementPlainHttpAuditTest` with:

```
Failed to start quarkus / Port already bound: 8081
```

That is an **environmental** failure caused by the previous run's kill, and it reads **exactly**
like a branch-introduced test failure in a test the branch never touched. This is the provenance
trap that `persona-plan-marshall-agent` Principle 8 exists for — the signal moves with the
environment, not with the code.

## Workaround that works today

Pass an explicit `--timeout` on the build invocation rather than relying on the learned value:

```bash
python3 .plan/execute-script.py plan-marshall:build-maven:maven run \
  --command-args "verify" --timeout 600
```

Note `--command-args` takes ONE quoted string; `--timeout` is a sibling flag, not part of it.

## What the mechanism should do instead

1. The learned timeout must not shrink below the observed duration of the same command — a learned
   value that under-provisions converts a slow build into a false red.
2. A timeout kill must be reported **as a timeout**, distinctly from a build failure, so the next
   reader does not chase a code defect.
3. A killed build should release, or at minimum report, the ports its JVM held, so the contamination
   does not migrate into an unrelated later run.

## Evidence

Plan `plan-v02-03-documentation-restructure` (epic `api-sheriff-0-2-0`), PR #197.
