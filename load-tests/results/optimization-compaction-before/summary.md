# Measured API load results

|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|00-coordinator-cache-hit-500|500|498.00|498.00|15.02|76.10|129.93|0.00|0|99|
|01-coordinator-cache-hit-1|1|1.96|1.96|13.33|16.00|16.24|0.00|0|0|
|02-coordinator-mixed-500-updates|500|496.54|281.98|7.22|138.90|290.01|43.21|104|99|

Latencies include only HTTP 200 responses. HTTP RPS includes failures. Dropped iterations were not sent. Exit 99 denotes a failed k6 threshold; all raw summaries are retained. These are short shared-host runs, not capacity guarantees.
