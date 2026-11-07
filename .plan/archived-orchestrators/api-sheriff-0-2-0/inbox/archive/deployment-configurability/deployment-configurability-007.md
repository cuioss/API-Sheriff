envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:24Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): A coverage-shaped check over an unpopulated detector reports zero, which reads as clean rather than unmeasured

Original lesson `2026-09-23-07-001` (component `api-sheriff`).

## What happened

PLAN-28 ran the deep planning lane, but `manage-findings assessment list --certainty CERTAIN_INCLUDE`
returned `total_count: 0` — no assessments had been filed. The Q-Gate's Assessment Coverage section
and its Missing Coverage step therefore ran over an empty detector population and emitted zero
findings across the 35 paths the seven deliverables declared.

Zero findings from an unpopulated sink is not the same fact as zero findings from a populated one,
but it renders identically in the report. The same run hit two further instances of the identical
shape: `pre-push-quality-gate` warned that `derive_gate_bundles` resolved 19/37/52/56 footprint
paths to no bundle (so those paths were not gated), and the same step warned that no module-tests
canonical resolves, leaving the scoped-green/whole-tree-red divergence class un-gated for every push.
All three degraded honestly by logging; none could have been detected from the verdict alone.

## Candidate rule

A coverage-shaped check must report its examined population (what it could read), not only its
finding count. Where the population is empty, the verdict is `unmeasured`, never `clean` — and the
run must say which. This is the generic unmeasured-vs-clean collapse: any check whose verdict is a
count over a population it did not itself construct will report `0` when the population is empty,
and every downstream reader treats `0` as a pass.

## Source

`deployment-configurability` PLAN-28 (PR #341), Q-Gate finding `09f896` (phase 3-outline, resolution
`accepted`). Disposition in that plan: accepted as a knowingly-vacuous check rather than treated as a
defect — all 7 deliverables landed as done tasks, so re-running deep-lane discovery afterwards would
have added no information.
