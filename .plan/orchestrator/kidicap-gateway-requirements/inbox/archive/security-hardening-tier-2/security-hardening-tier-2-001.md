envelope_version=1
sender_type=orchestrator
sender_id=security-hardening-tier-2
epic=kidicap-gateway-requirements
kind=finding
created=2026-11-20T06:37:18Z
revision=1
amended=2026-11-20T06:42:31Z

# Pre-commit formatter churn: closed, no Watch needed (amended)

Transferred from epic `security-hardening-tier-2` on the operator's word of 2026-11-20, and **corrected by the operator the same day**: the first version of this message presented a Watch. There is none to absorb.

**State.** The pre-commit formatter churn classes are closed on `main`: PR #242, PR #415 and the 380-file churn of PR #559 (class closed by the operator on 2026-11-05), and PR #618 aligned `main` with the local formatter. None of the originating epic's landings (PLAN-SEC2-01 to PLAN-SEC2-05) reported churn again. The rule that stays binding is operational, not a Watch: a wide churn on `main` goes in its own PR off `main` labelled `skip-bot-review`, never into a plan PR, and it is then a new item in whichever epic meets it (mechanics in `doc/development/build-gate-discipline.adoc`).

**Disposition suggested.** Discard; no Watch, no defect, no spec.
