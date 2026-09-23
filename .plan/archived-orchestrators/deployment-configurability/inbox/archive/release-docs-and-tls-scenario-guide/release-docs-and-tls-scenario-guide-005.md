envelope_version=1
sender_type=plan
sender_id=release-docs-and-tls-scenario-guide
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-15T18:22:49Z

component=documentation
category=bug
source_finding=pr-comment 4ca8d6 (CodeRabbit, fixed in-run)

# TLS scenario guide stated a conditional fallback as unconditional

## What happened

`doc/user/tls-scenarios.adoc` (new in PR #305) described the management interface's plain-HTTP fallback as happening whenever the certificate pair is not supplied. The real resolution in `ResolvedServerTlsMaterial.resolvesToPlainHttp` is a three-leg order: a selected named bucket decides (a key-less named bucket forces plain HTTP even when the certificate pair is set); otherwise a key-bearing default bucket; otherwise the management certificate keys in any spelling; only when none supplies key material does management serve plain HTTP and emit `WARN ApiSheriff-115`. CodeRabbit caught it (CWE-16 framing); fixed by TASK-11.

## Corrective action

For security-relevant fallback behaviour in operator docs, derive the prose from the resolver code path, not from the common case: read the resolving method and state every leg in its precedence order, including the legs that force the insecure outcome despite apparently-sufficient configuration. Cite the class so the claim can be re-verified.

## Evidence

- Plan: release-docs-and-tls-scenario-guide (PR #305)
- Finding hash: 4ca8d6, thread PRRT_kwDOPatrT86il0gf
