envelope_version=1
sender_type=orchestrator
sender_id=security-hardening-tier-2
epic=kidicap-gateway-requirements
kind=finding
created=2026-11-20T06:37:18Z

# Transferred Watch: pre-commit formatter churn

Transferred from epic `security-hardening-tier-2` (being closed after PLAN-SEC2-06) on the operator's word of 2026-11-20. Please absorb it as a Watch in this epic.

**Watch.** Pre-commit formatter churn, carried over from the closed epic `security-hardening-tier-1`: PR #618 aligned `main` with the local formatter. A plan that reports churn again re-opens it, and the recurrence goes to its own skip-bot-review PR off `main` (see the repository's `doc/development/build-gate-discipline.adoc`).

**State at transfer.** No plan of the originating epic reported churn again: PLAN-SEC2-01 to PLAN-SEC2-05 shipped (#623, #627, #626, #635, #633) without a re-opening. PLAN-SEC2-06 is running and has not reported one.

**Retire when** a further epic has shipped without reporting churn, or on the operator's word.
