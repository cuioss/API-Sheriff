envelope_version=1
sender_type=plan
sender_id=plan-32-early-answer-it-timeouts
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-15T11:52:04Z

# Candidate lesson: build-server jobs reported timeout under foreign load (clipped, not hung)

- Source: plan work log build-server wait results job_status=timeout (job c880840d at 2026-10-14T20:54:17Z, job e4e96909 at 2026-10-15T08:28:19Z), each followed by a successful re-run
- Plan: plan-32-early-answer-it-timeouts

## Observation

Two long Maven runs through the build server ended in `job_status=timeout` while other work loaded the machine. The second (08:28) was re-submitted at once and succeeded at 08:36 with no code change. The first fell in the TASK-8 window and the task completed afterwards.

## Candidate rule

A build-server `timeout` is first read as "clipped by the time limit". Check machine load and the Maven log tail before blaming the change, then re-submit once. This matches the existing project memory "Local Gate Timeout Under Foreign Load". It recurred here on the daemon path, so the rule also covers build-server jobs, not only direct gate runs.
