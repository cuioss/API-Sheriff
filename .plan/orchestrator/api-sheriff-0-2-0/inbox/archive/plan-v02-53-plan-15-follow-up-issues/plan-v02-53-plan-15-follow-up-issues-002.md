envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:06:45Z

## Candidate lesson: a doc change to a class's reader list missed the second enumeration in package-info (D1)

- Source signal: Q-Gate finding 5a5645 (phase 3-outline, scope_criterion_validator, under_coverage, deliverable 1), resolved in-run (fixed)
- Plan: plan-v02-53-plan-15-follow-up-issues (PR #565, merge 7a5d52a)
- Surface touched: api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/package-info.java, LocationPathReview Javadoc

### What happened

D1 updated the LocationPathReview class Javadoc to name the BFF return URL as a third reader of the review. The package Javadoc in `http/package-info.java` enumerates the same readers independently (it named two) and was not in D1's affected files; it was only in D5's for an unrelated count update. After D1 the class doc and the package doc would have disagreed.

### Resolution in-run

package-info.java added to D1 with the reader-enumeration update; during that, a further existing reader (PortalRules) was found and added, so both docs name every reader. Shared-file ordering recorded (D1 writes first, D5 depends on D1).

### Candidate rule

When a deliverable changes an enumeration in documentation ("read by A and B"), search for every other place the same enumeration is stated (package-info, ADRs, user docs) before fixing the affected-file list, and check the enumeration against the code rather than extending it by one.
