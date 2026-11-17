# Measured API load results

|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|00-coordinator-cache-hit-500|500|498.85|498.85|1.28|2.85|6.45|0.00|0|0|
|01-coordinator-cache-hit-1|1|1.98|1.98|4.50|5.61|5.71|0.00|0|0|
|02-coordinator-mixed-500-updates|500|500.01|500.01|0.81|6.77|32.20|0.00|0|0|

Latencies include only HTTP 200 responses. HTTP RPS includes failures. Dropped iterations were not sent. Exit 99 denotes a failed k6 threshold; all raw summaries are retained. These are short shared-host runs, not capacity guarantees.
