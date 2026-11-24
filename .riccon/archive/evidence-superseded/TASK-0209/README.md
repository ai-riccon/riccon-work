# TASK-0209 superseded evidence

- EV-1000: first 1000-object perf run; harness propagated parent PERF_M05_CONNECTOR=1 to k6 after connector branch failed; orchestration exit 1, not a product result.
- EV-1001: second 1000-object run; API/worker jar was stale (pre-B6), POST /knowledge/connectors returned 404; orchestration exit 1.
- EV-1002 (active) is the authoritative passing run after rebuilding jars.
