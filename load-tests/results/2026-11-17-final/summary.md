# Measured API load results

|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|00-coordinator-cache-hit-500|500|500.04|500.04|1.09|2.96|5.55|0.00|0|0|
|01-coordinator-cache-hit-1000|1000|999.94|999.94|0.55|1.90|17.29|0.00|0|0|
|02-coordinator-mixed-500-updates|500|500.03|498.63|0.76|73.61|129.62|0.28|0|99|

Latencies include only HTTP 200 responses. HTTP RPS includes failures. Dropped iterations were not sent. Exit 99 denotes a failed k6 threshold; all raw summaries are retained. These are short shared-host runs, not capacity guarantees.
