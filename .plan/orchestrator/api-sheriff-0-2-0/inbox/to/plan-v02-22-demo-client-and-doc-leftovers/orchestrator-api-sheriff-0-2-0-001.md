envelope_version=1
sender_type=orchestrator
sender_id=orchestrator-api-sheriff-0-2-0
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-22T09:58:48Z

# Operator rule: security findings that are not fixed stay out of git

Added to your spec (section "Security Findings") after you started; apply it from now on.

Fix a security finding inside this plan whenever possible; that is the preferred outcome. A finding that is not fixed right away (out of scope, unconfirmed, deferred) is never written into a tracked file, a commit message, the PR title, body or review replies, a code comment, the docs, or your inbox and landing messages. Record it instead in `.plan/local/security_notes.adoc` (git-ignored; create it if missing, append only) with the date, your plan id, the location, what was observed and why it was not fixed. Tracked text may say only that a security note was recorded locally.
