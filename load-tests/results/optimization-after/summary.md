# Measured API load results

|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|00-local-cache-hit-500|500|499.96|499.96|1.04|3.72|11.84|0.00|0|0|
|01-local-cache-miss-500|500|500.05|500.05|0.71|2.28|6.23|0.00|0|0|
|02-local-short-prefix-500|500|500.05|500.05|0.76|1.62|2.57|0.00|0|0|
|03-local-fuzzy-500|500|500.05|500.05|0.84|1.84|3.91|0.00|0|0|
|04-coordinator-cache-hit-500|500|500.05|500.05|0.79|1.81|8.92|0.00|0|0|
|05-coordinator-cache-miss-500|500|500.03|500.03|2.12|16.88|41.05|0.00|0|0|
|06-coordinator-short-prefix-500|500|500.04|500.04|0.72|1.76|2.92|0.00|0|0|
|07-coordinator-fuzzy-500|500|499.98|499.98|1.23|3.04|5.71|0.00|0|0|
|08-local-cache-hit-1000|1000|999.89|999.89|0.48|1.54|4.71|0.00|0|0|
|09-coordinator-cache-hit-1000|1000|999.96|999.96|0.53|1.40|3.42|0.00|0|0|
|10-coordinator-mixed-500-updates|500|499.98|499.98|0.67|7.04|39.03|0.00|0|0|

Latencies include only HTTP 200 responses. HTTP RPS includes failures. Dropped iterations were not sent. Exit 99 denotes a failed k6 threshold; all raw summaries are retained. These are short shared-host runs, not capacity guarantees.
