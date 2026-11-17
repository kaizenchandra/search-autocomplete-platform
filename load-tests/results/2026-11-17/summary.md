# Measured API load results

|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|00-local-cache-hit-500|500|499.90|499.90|1.51|4.83|13.91|0.00|0|0|
|01-local-cache-miss-500|500|499.58|499.58|8.13|47.01|71.84|0.00|0|99|
|02-local-short-prefix-500|500|500.04|500.04|2.16|21.74|51.94|0.00|0|99|
|03-local-fuzzy-500|500|500.01|500.01|1.49|8.71|28.65|0.00|0|0|
|04-coordinator-cache-hit-500|500|498.60|487.70|1.98|51.10|262.50|2.19|14|99|
|05-coordinator-cache-miss-500|500|499.84|499.84|5.33|38.98|65.80|0.00|0|99|
|06-coordinator-short-prefix-500|500|500.04|500.04|1.08|6.79|14.38|0.00|0|0|
|07-coordinator-fuzzy-500|500|500.03|500.03|1.45|4.27|13.06|0.00|0|0|
|08-local-cache-hit-1000|1000|986.84|986.84|1.24|10.90|47.44|0.00|128|0|
|09-coordinator-cache-hit-1000|1000|999.99|999.99|0.89|4.43|11.38|0.00|0|0|
|10-coordinator-mixed-500-updates|500|499.91|49.32|0.68|2.20|4.31|90.13|0|99|

Latencies include only HTTP 200 responses. HTTP RPS includes failures. Dropped iterations were not sent. Exit 99 denotes a failed k6 threshold; all raw summaries are retained. These are short shared-host runs, not capacity guarantees.
