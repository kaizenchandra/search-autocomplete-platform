# Measured API load results

|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|00-local-cache-hit-500|500|491.89|491.89|7.81|187.71|351.53|0.00|76|99|
|01-local-cache-miss-500|500|470.02|470.02|11.88|158.55|787.45|0.00|299|99|
|02-local-short-prefix-500|500|499.51|499.51|3.80|32.41|48.64|0.00|0|0|
|03-local-fuzzy-500|500|499.71|499.71|3.93|24.34|39.47|0.00|0|0|
|04-coordinator-cache-hit-500|500|500.00|500.00|2.55|22.12|60.61|0.00|0|99|
|05-coordinator-cache-miss-500|500|497.57|481.25|25.01|101.32|151.67|3.28|0|99|
|06-coordinator-short-prefix-500|500|499.62|499.62|4.19|25.47|42.68|0.00|0|0|
|07-coordinator-fuzzy-500|500|475.31|249.06|45.14|209.17|352.83|47.60|228|99|
|08-local-cache-hit-1000|1000|991.53|991.53|5.79|49.48|112.50|0.00|76|99|
|09-coordinator-cache-hit-1000|1000|993.27|993.27|1.54|17.38|98.34|0.00|67|99|
|10-coordinator-mixed-500-updates|500|496.71|421.54|3.13|96.24|171.77|15.13|45|99|

Latencies include only HTTP 200 responses. HTTP RPS includes failures. Dropped iterations were not sent. Exit 99 denotes a failed k6 threshold; all raw summaries are retained. These are short shared-host runs, not capacity guarantees.
