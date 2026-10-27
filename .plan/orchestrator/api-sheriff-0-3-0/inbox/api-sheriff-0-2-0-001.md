envelope_version=1
sender_type=orchestrator
sender_id=api-sheriff-0-2-0
epic=api-sheriff-0-3-0
kind=finding
created=2026-10-27T14:53:25Z

# PLAN-V02-06 shipped without the per-client detection substrate: your gate 2 now waits on PLAN-V02-50

Source: epic api-sheriff-0-2-0, analyze of the PLAN-V02-06 landing on 2026-10-27.

`PLAN-V02-06` shipped as #528 (`c957ee51`, ADR-0073), but only its existence-oracle half: the uniform `404` for untrusted callers (spec D1), the response-timing floor (D2, `edge/end/ResponseDelay`) and the `rate_limit` boot WARN (D9). The operator split the rest off at outline. The per-client detection substrate (D3), the response on trip with the block/ban and delay primitive (D4), the substrate contract (D7) and the HTTP/2 stream-abuse bounds (D8) are not on `main`. They are now **`PLAN-V02-50` (`enumeration-detection-substrate`)** in `api-sheriff-0-2-0`, staged and not yet running, with D1/D3 for the substrate and its contract and D2 for the trip response.

What this means for `api-sheriff-0-3-0`:

- Gate (2) of this epic ("`PLAN-V02-06` per-client detection substrate shipped") is **still closed**. It opens when `PLAN-V02-50` ships, not when `PLAN-V02-06` did.
- `PLAN-V03-01` names `PLAN-V02-06` D4/D7 as the owner of the block/ban primitive and the non-blocking delay. Re-point those to `PLAN-V02-50` D2/D3. One piece already exists: `edge/end/ResponseDelay` is a non-blocking delay on a Vert.x timer, built for the timing floor.
- Re-ground `PLAN-V03-01` against what `PLAN-V02-50` ships, not against its spec.
