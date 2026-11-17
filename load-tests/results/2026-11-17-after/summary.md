# Measured API load results

|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|00-local-cache-hit-500|500|499.32|499.32|1.64|7.14|12.03|0.00|0|0|
|01-local-cache-miss-500|500|500.03|500.03|2.78|15.18|36.45|0.00|0|0|
|02-local-short-prefix-500|500|496.96|496.96|2.24|17.35|63.74|0.00|0|99|
|03-local-fuzzy-500|500|499.97|499.97|1.49|11.51|28.86|0.00|0|0|
|04-coordinator-cache-hit-500|500|499.25|499.25|4.36|34.70|143.22|0.00|0|99|
|05-coordinator-cache-miss-500|500|492.04|393.55|41.79|177.64|221.26|20.02|0|99|
|06-coordinator-short-prefix-500|500|499.34|499.34|2.25|16.98|38.34|0.00|0|0|
|07-coordinator-fuzzy-500|500|499.45|456.40|10.95|80.64|160.79|8.62|0|99|
|08-local-cache-hit-1000|1000|999.96|999.96|1.73|14.97|24.05|0.00|0|0|
|09-coordinator-cache-hit-1000|1000|1000.00|1000.00|1.40|13.26|22.61|0.00|0|0|
|10-coordinator-mixed-500-updates|500|499.65|484.92|2.33|94.51|156.88|2.95|5|99|

Latencies include only HTTP 200 responses. HTTP RPS includes failures. Dropped iterations were not sent. Exit 99 denotes a failed k6 threshold; all raw summaries are retained. These are short shared-host runs, not capacity guarantees.
