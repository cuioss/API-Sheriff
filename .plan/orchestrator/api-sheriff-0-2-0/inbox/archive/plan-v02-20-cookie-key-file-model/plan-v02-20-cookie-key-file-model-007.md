envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:34:29Z

# Candidate lesson: docs stated a limit and a key-change procedure more loosely than the code implements

- Source: automatic-review signal; CodeRabbit PR #527 findings d62e42 (ADR-0072:75) and 541de9 (doc/user/bff-cookie.adoc:237), both resolution fixed in 36229c1c; review_body 1a80c5 answered
- Suggested component: project-local (documentation of bounded reads and boot-time key loading)

## What happened

1. The docs said the key file read is bounded at 256 bytes. KeyFiles reads `maxBytes + 1` (257) to detect an oversized file, so the file limit and the read limit differ by one byte. Corrected in ADR-0072, both bff-cookie docs and doc/configuration.adoc.
2. The key-change procedure said to replace the key file. The gateway resolves the key once at boot (BffRuntimeProducer.cookieSessionBinding), so replacing the file without a restart changes nothing. The docs now require replacing the file on every replica and restarting every replica.

Both slipped past the in-plan functional and code reviews and were caught by the review bot.

## Corrective rule

When documenting a size bound, state the file limit and name the detect-oversize read separately. When documenting an operator procedure for material loaded at boot, state the restart step explicitly in the procedure itself, not only in a separate "reads the file once" sentence.
