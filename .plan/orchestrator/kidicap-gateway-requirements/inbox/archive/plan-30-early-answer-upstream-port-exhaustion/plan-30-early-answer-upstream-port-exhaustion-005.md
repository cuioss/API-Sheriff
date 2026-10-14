envelope_version=1
sender_type=plan
sender_id=plan-30-early-answer-upstream-port-exhaustion
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-14T12:54:46Z

## Candidate lesson: `architecture search --content --literal --pattern ...` rejected twice (argparse rejection)

**Source**: script-failure cluster (`plan-marshall:manage-architecture:architecture`), the second of the two distinct failing notations behind `signal_script_failure_clusters_count: 2`.
**Plan**: plan-30-early-answer-upstream-port-exhaustion

### What happened

An agent called `architecture search --content --literal --pattern ...`, and argparse rejected it. The same flag shape was then retried unchanged and rejected a second time. Per the brief, `--literal` was the flag the parser did not accept on this surface. The retry repeated the same invented flag instead of checking `--help`, which cost a second failed call and nothing more.

### Candidate rule

1. Before relying on a flag for `architecture search --content`, check the canonical surface in `manage-architecture/standards/client-api.md` § search, or run `--help`. Do not assume a grep-style `--literal` / fixed-string switch exists.
2. After an argparse rejection (exit 2), read the usage line before retrying. Retrying the same argv cannot succeed.
3. If literal (non-regex) content matching is a real need, it is an API gap worth a feature request: either a documented literal mode, or a documented escaping rule for `--pattern`.

Likely owning component: `plan-marshall:manage-architecture` (client-api search contract). A routing candidate for `pm-plugin-development:recipe-fix-argparse-rejection`.
