envelope_version=1
sender_type=plan
sender_id=plan-28-closeout-residual-hardening
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T06:14:43Z

# Candidate lesson: a coverage check over an empty population reports zero, and zero reads as clean

**Source signal**: Q-Gate finding `09f896` (phase 3-outline, type `triage`, severity warning, resolution `accepted`)
**Component**: `plan-marshall:phase-3-outline` — Q-Gate assessment-coverage section

## What happened

PLAN-28 ran the **deep** planning lane (`status.metadata.planning_lane: deep`), but
`manage-findings assessment list --certainty CERTAIN_INCLUDE` returned `total_count: 0` — no
assessments had been filed. The Q-Gate's Assessment Coverage section (2.2) and its Missing Coverage
step (5) therefore ran over an empty detector population and emitted zero findings across the
**35 paths** the seven deliverables declared.

Zero findings from an unpopulated sink is not the same fact as zero findings from a populated one,
but it renders identically in the report. The Q-Gate correctly refused to emit 35 per-file
missing-assessment flags off an unwritten sink (that would have reported *the sink's absence* as 35
real defects), and instead recorded the vacuity explicitly.

## Why this is candidate-lesson shaped

This is the generic **unmeasured-vs-clean collapse**: any check whose verdict is a count over a
population it did not itself construct will report `0` when the population is empty, and every
downstream reader treats `0` as a pass. The only defence is for the check to publish the population
it examined alongside the count.

The same run hit two further instances of the identical shape — `pre-push-quality-gate` warning
that `derive_gate_bundles` resolved 19/37/52/56 footprint paths to **no bundle** (so those paths
were not gated), and the same step warning that **no module-tests canonical resolves**, leaving the
scoped-green/whole-tree-red divergence class un-gated for every push in this plan. All three
degraded honestly by logging; none of the three could have been detected from the verdict alone.

## Candidate rule

A coverage-shaped check must report its examined population (what it could read), not only its
finding count. Where the population is empty, the verdict is `unmeasured`, never `clean` — and the
run must say which.

## Disposition in this plan

Accepted as a knowingly-vacuous check rather than treated as a defect: all 7 deliverables landed as
done tasks, so re-running deep-lane discovery afterwards would have added no information. Recorded
explicitly so the zero is read as unmeasured.
