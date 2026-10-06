envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-02T21:08:14Z

# Candidate lesson: a long integration run can spend its whole budget on an environment stall, and a `timeout` status says nothing about the change

## Pattern

The integration profile builds a native image, builds container images and starts a compose stack before the first test runs. A stall in that preamble, here an image pull that hung, is indistinguishable from a slow run until the budget is gone. The run then ends `timeout` with zero tests executed. Nothing about the change has been learned, an hour has been spent, and the status reads like a verdict.

A smaller instance of the same class: a build submitted without an explicit timeout is clipped at the executor's 300 s default even when the job is healthy and still making progress.

## What the record shows

- First integration-profile run on the migrated stack: `status timeout` at 3600 s "with NO integration test executed". The native image and the gateway image built, the identity provider started and became ready, then the compose build of a helper image stalled at loading base-image metadata from the public registry. A plain HTTPS request to the registry answered in 0.3 s from the host, while the container runtime's pull and manifest inspection both hung with no output. Recorded as "not a verdict on the change"; execution was paused until the next morning (decision log 002f29; work log 35886a, the job polled `running` from 300 s to 3304 s with an estimate of 135 s throughout).
- The rerun the next morning executed and gave a real result (work log dab5f9, then decision log 577d6f for the diagnosis of that result).
- 300 s default clips: a targeted Failsafe run was cut "by the executor's 300 s default after 11 green classes" and had to be re-run as a second slice with an explicit timeout (work log 62f6f7, e0332d); an orchestrator-tier gate ended `timeout` one second after a 300 s poll that still showed `running`, and was resubmitted (6038ff).
- Unrelated to the registry but the same shape: three consecutive execute dispatches ended in harness cancellation on a provider overload before changing anything; the tree was verified unchanged after each and execution paused for about three and a half hours (decision log 5cc2bf).

## How to recognise it next time

- The job has been `running` for many multiples of its estimate and the build log's last line is an image pull or a metadata load.
- `status: timeout` and the Failsafe or Surefire report directory holds no report from this run.
- A wait result flips from `running` to `timeout` at almost exactly 300 s.

## Suggested direction (for the orchestrator to classify)

- Before a run that needs container images, pull the base images (or probe the runtime's registry access with a short timeout) as a separate, cheap step; a host-side HTTPS probe is not sufficient, the container runtime's own path is what stalls.
- On `timeout`, check whether any test report was written before classifying; "timed out with zero tests" is an environment outcome and should be reported as such.
- Pass an explicit timeout on every build submission whose expected duration is over five minutes; the estimate the build server already holds could be used to warn when the requested budget is below it.
- The existing project note on clipped local gates under foreign load covers a neighbouring case; this one is a stall in the image preamble rather than load.
