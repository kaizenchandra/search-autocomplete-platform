# Measured sharded serving and update report — 2026-11-17

Implemented code and reproduction: [distributed index](distributed-index.md), [load harness](../load-tests/README.md).

## Experiment

20,000 deterministic `catalog phone NNNNN` suggestions in the `load` category, plus ten existing demo suggestions. macOS arm64 shared host, 11 logical CPUs, 18 GiB RAM; Java 21.0.13.0.3, k6 2.4.0. Four separate JVMs (512MiB maximum heap each): one all-local two-shard comparison, one coordinator with no owned Trie partitions, and two workers owning one shard each. PostgreSQL 17 and Redis 7 ran in local Docker. Traffic was loopback HTTP, not TLS or cross-AZ networking. Other workspace processes were active; the host was not isolated.

Each workload ran once for 10 seconds (updates for 15 seconds). The generator used constant arrival rate, 100 preallocated / 300 maximum VUs, and a two-second client deadline. The process-local limiter was explicitly raised to 10,000 RPS/burst 10,000 for this benchmark; defaults remain 200/500. Two warmup prefix requests were sent; there was no exhaustive JIT warmup. Sequence, JIT, scheduling, cache/DB contention and other host activity prevent treating these runs as a controlled statistical A/B experiment.

Both result sets retain raw k6 JSON/logs, exits, process resource samples and server metrics. Artifact SHA-256 fingerprints distinguish binaries. [Initial raw results](../load-tests/results/2026-11-17/summary.md) exposed a revision-convergence failure; [repeat raw results](../load-tests/results/2026-11-17-after/summary.md) follow bounded worker revision history/catch-up, single-flight coalescing and refill correctness fixes. Baseline failures were not deleted.

## Final guardrail build: focused confirmation

After the full matrix, review tightened event retention to prune complete revisions and based replay freshness on the database-read time rather than the end of replay. The final build (`8f66a9fd9685c62f60cb7ff244f0295557042680457403a6fb9e357612008f16`) passed 24 Java tests and was separately measured with the same catalog/process setup:

|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|00-coordinator-cache-hit-500|500|500.04|500.04|1.09|2.96|5.55|0.00|0|0|
|01-coordinator-cache-hit-1000|1000|999.94|999.94|0.55|1.90|17.29|0.00|0|0|
|02-coordinator-mixed-500-updates|500|500.03|498.63|0.76|73.61|129.62|0.28|0|99|

This focused run returned 999.94 successful RPS at 1,000 offered RPS with successful-response p99 17.29ms and no errors/dropped iterations. Under 20 requested updates/sec, it returned 498.63 successful query RPS at 500 offered RPS, p99 129.62ms and 0.28% HTTP 503s; the latency threshold still failed. Actual commits were 267 over 16.069s, or 16.62/sec, with commit p50 4.11ms and p99 62.89ms. Of 15 visibility probes, 14 completed within three seconds; completed-sample median was 854.38ms and maximum 2,080.87ms, and one remained censored beyond three seconds. These observations still do not establish the freshness SLO.

[Focused raw results](../load-tests/results/2026-11-17-final/summary.md) and the fingerprinted [environment](../load-tests/results/2026-11-17-final/environment.json) are retained. This smaller confirmation did not repeat the distinct-prefix/fuzzy miss workloads; their full-matrix failures below remain unresolved. The full-matrix fingerprint is `be612d23ebc31f59698d842361990e60a10d41419e66824fceb185cfc3a7cec7`. Do not combine the two runs into an asserted capacity envelope.

## Full repeat matrix measurements

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

Latencies above include only successful HTTP 200 responses. Errors are separately counted; low successful-response latency does not imply that rejected requests met an SLO. HTTP RPS includes failures. Offered RPS is the configured arrival target. Dropped iterations were never sent. Exit 99 means a latency/error threshold failed; other counters and output remain valid.

The 1,000-RPS coordinator cache-hit run achieved 1,000 successful RPS, p99 22.61ms, no HTTP failures or dropped iterations, and approximately 99.81% exact-cache hits. This measures mostly cached serving, not 1,000 uncached distributed Trie lookups per second. The local 500-RPS distinct-prefix miss run achieved 500.03 successful RPS and p99 36.45ms without errors.

The repeat coordinator miss run achieved only 393.55 successful RPS at 500 offered RPS, with 20.02% HTTP 503s and successful-response p99 221.26ms. Its Redis error counter increased by 441. The coordinator fuzzy run returned 8.62% 503s. The local short-prefix and coordinator 500-RPS hit rows also missed the 50ms p99 target. These are unresolved performance/availability failures. Guarded request failures were 503, not fabricated successful responses; no 429, 500 or transport-status-0 failures occurred in the repeat matrix. The evidence does not isolate the relative contribution of host scheduling, Redis command deadlines, fan-out saturation and cold-path execution.

## Updates and visibility

The initial coordinator mixed run returned only 49.32 successful RPS and 90.13% HTTP errors at 500 offered RPS. Strict current-version-only workers rejected most coordinator requests during updates. Bounded per-revision history and on-demand catch-up preserve one-version results while improving this behavior.

In the repeat mixed run, 500 offered RPS produced 484.92 successful RPS, 2.95% HTTP 503s, five dropped iterations and successful-response p99 156.88ms. This is a substantial availability improvement in these observations, but **still fails** the 1% error / 50ms p99 thresholds. It is not proof of production readiness or an isolated causal performance comparison.

The writer requested 20 updates/sec; 259 transactions committed over 16.382 seconds, achieving 15.81 commits/sec. Database commit p50 was 7.10ms and p99 407.72ms. Automatic compaction occurred: the starting revision was 1027, final revision 1286, and a durable 20,010-term checkpoint was present at 1286. Compaction still locks the revision row and can delay writers.

Visibility probes sampled one updated ID per second: 14 of 15 became visible within three seconds. Among completed probes, median visibility was 299.28ms and maximum 761.33ms; **one probe exceeded three seconds**, so an overall visibility p99 below that window is not established. These are client-observed commit-ack-to-result times, not just database commit latency. Full raw writer/probe results: [updates JSON](../load-tests/results/2026-11-17-after/10-coordinator-mixed-500-updates-updates.json).

## Failure and resource evidence

Six exact-prefix comparisons between independently served local and remote results matched IDs, order, scores, highlights and revision after convergence. An internal request without the service credential returned 403; direct public autocomplete on a single-shard worker returned 503 rather than a misleading partial corpus. Temporarily pausing an owned worker caused an uncached coordinator query to fail with 503 in 206.26ms; the worker was resumed. [Fault probe output](../load-tests/results/2026-11-17-after/fault-probe.json).

During the repeat update row, sampled maximum RSS was 359.3MiB / 362.9MiB for workers, 286.0MiB for the coordinator and 394.0MiB for the local comparison process. These are sampled resident pages, not heap maxima or memory per term. The coordinator's ending heap-used gauge was about 79.68MiB and aggregate GC pause time increased by 0.087s during that row; no GC pause percentile is inferred from this. Original metric/resource samples are retained.

## Verification and remaining risk

24 Java tests pass: Trie and fuzzy reference comparisons; sharded results versus reference for 1/2/4/8/16 shards; deterministic global merge; hidden-duplicate deletion refill; immutable concurrent readers; real DB replay, tombstones, compaction, gaps/duplicates, durable restore and metadata; intermediate worker history and catch-up; HTTP routing/version/shard-count checks; bounded/coalesced loads; whole-revision retention boundary; existing admin/analytics/privacy and real signed-JWT validation. Tests used a separate disposable `autocomplete_verify` database to avoid benchmark-fixture interference. Compose override syntax and Python drivers were validated. The production Next.js build and all five browser regression tests passed against the sharded coordinator.

No 10K/50K/100K-RPS run, long endurance experiment, multi-AZ failover, sharded metadata ownership, S3 distribution, live rebalancing/replica registry, or distributed generator was executed. The 100K corpus cap, write-locking compaction, short history window, non-hard request cancellation and full metadata replication remain limitations. The measured miss/update failure rates must be resolved before production admission.
