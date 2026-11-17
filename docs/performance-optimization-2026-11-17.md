# Distributed miss and compaction optimization — measured before/after

## Experiment and evidence

The original implementation was rerun with neutral JFR stage instrumentation, then the same fixture, workload ordering, rates, deadlines, thresholds, heap limits and JFR profile were used for the optimized build. Both full matrices and additional matched 30-second update runs are retained. The host was shared macOS arm64 (11 logical CPUs, 18GiB RAM), Java 21.0.13.0.3, k6 2.4.0; 20,000 synthetic catalog terms plus ten demo terms; two worker JVMs, one metadata coordinator and one local comparison JVM, each limited to 512MiB heap; Docker PostgreSQL/Redis. Loopback HTTP is not multi-AZ production traffic. Process limiter: 10,000 RPS/burst 10,000 for both experiments; request deadline 180ms, fan-out deadline 200ms, k6 deadline 2s unchanged.

Before fingerprint: `ca20327048435b0394f1ae422c2be85e6933f285e55629ae67f1e06b37f7985c`.
After fingerprint: `38a252b4d0676d42fe686ce44eeda4901b9c1e7830fe9e1a6108b0df69baa1f9`.

Raw results: [before full matrix](../load-tests/results/optimization-before/summary.md), [after full matrix](../load-tests/results/optimization-after/summary.md), [before compaction run](../load-tests/results/optimization-compaction-before/summary.md), [after compaction run](../load-tests/results/optimization-compaction-after/summary.md). Each directory contains JSON/logs, exit codes, timestamps, metric deltas and sampled resources. Failed baseline runs are preserved.

## Profile findings

Worker 0 recorded 120 `Corpus` monitor waits during the baseline 15-second update window: recorded p99 806.23ms, maximum 828.63ms, and overlapping aggregate wait 26.36s. Even the steady miss window recorded 56 Corpus waits (maximum 70.29ms). `snapshotAt` shared the monitor with polling/replay/building, serializing otherwise immutable reads.

Worker CPU samples in the update window repeatedly included `TrieIndex.offer` (18 inclusive samples), `String.replaceAll` (15), normalization (10), log1p/scoring (10), and regex compilation (7). Inclusive counts overlap and are not exact CPU percentages. Compaction performed repeated normalization and log calculations for each prefix node and comparison.

The coordinator's baseline recorded slow Redis GET/SET work in the request path (recorded miss-window p99 above the 1ms JFR threshold: GET 50.80ms, SET 43.81ms). The eight blocking RPC threads shared a 64-task queue; worker stalls propagated through queued fan-out and deadlines. The miss window recorded 66 Redis errors. These measurements identify a combined synchronization/CPU/queue/cache-write problem, not a need to relax deadlines.

The 30-second baseline crossed the compaction threshold: one worker event covered an 8,411.97ms **lock-acquisition-plus-work scope** on `scheduling-1`. The event starts before acquiring the revision lock, so this is not a precise isolated lock-hold measurement. Code inspection confirms index construction, serialization, artifact I/O and retention ran under the revision-row lock. The instrumented baseline writer achieved only 7.60 commits/sec in that run.

Profile summaries: [before worker](../profiling/before/worker0-summary.json), [after worker](../profiling/after/worker0-summary.json), [before coordinator](../profiling/before/coordinator-summary.json), [after coordinator](../profiling/after/coordinator-summary.json), [compaction stages](../profiling/compaction-comparison.json). Stage events are censored below 1ms; monitor events follow JFR's recording threshold. These distributions are not whole-request latency percentiles.

## Changes

- Export revision, terms and ranking policies in one read-only REPEATABLE READ transaction. End that transaction before construction. No revision-row lock is taken for export, construction, serialization, persistence or retention.
- Ordinary compaction runs in one background task per process, guarded against queuing additional jobs. Polling and immutable serving continue during construction.
- Before activation, replay the tail through the currently served revision. Missing coverage, rebuild markers and obsolete candidates cannot overwrite newer state. Concurrent deletes and context moves remain in the overlay. Publication never regresses the serving revision.
- Trigger compaction at 256 IDs/revisions; permit at most 1,024 IDs/revisions while it runs. The hard bound retains a correctness-first synchronous fallback. Explicit admin rebuild and structural/ranking rebuilds remain synchronous within that process, but no longer take the global writer lock.
- Current and retained-version reads use AtomicReference/concurrent history lookups. Only catch-up needs the Corpus monitor.
- Fan-out uses virtual threads for blocking HTTP, with explicit limits of 64 requests and 128 active RPCs; no eight-thread blocking queue. Existing timeouts, consistency checks and fail-closed behavior remain.
- Compile whitespace regex once. Cache normalized keys and scores during Trie construction instead of recomputing them for every prefix-node offer/comparison. This adds bounded O(terms) cache references per local Trie.
- Populate Redis through two workers and a 256-task queue. Queue saturation drops optional writes and records a counter. Redis SET no longer extends query latency; stale asynchronous writes have old revision keys and cannot bypass eligibility checks.

## Matched results

All latencies below are successful HTTP-response p99; errors and dropped iterations are separate.

|Workload|Offered RPS|Successful RPS before → after|p99 ms before → after|HTTP error % before → after|Dropped before → after|
|---|---:|---:|---:|---:|---:|
|Distributed distinct-prefix miss, 10s|500|481.25 → 500.03|151.67 → 41.05|3.28 → 0|0 → 0|
|Distributed fuzzy, 10s|500|249.06 → 499.98|352.83 → 5.71|47.60 → 0|228 → 0|
|Coordinator cache hit, 1,000 RPS, 10s|1,000|993.27 → 999.96|98.34 → 3.42|0 → 0|67 → 0|
|Mixed plus updates, 15s|500|421.54 → 499.98|171.77 → 39.03|15.13 → 0|45 → 0|
|Mixed plus updates, 30s|500|281.98 → 500.01|290.01 → 32.20|43.21 → 0|104 → 0|

Every row in the optimized full matrix and longer run passed its configured p99<50ms and error<1% thresholds, with no dropped iterations. The measured miss/update rows had no Redis errors and no dropped cache writes after optimization. This is observed behavior in these short runs, not proof that errors cannot occur under all workloads.

### Writes and visibility

Both writers requested 20 updates/sec using the same acknowledged, paced SQL driver over 128 fixture IDs. This is a requested rate, not an open-loop guarantee of offered writes.

|30-second update experiment|Before|After|
|---|---:|---:|
|Committed updates|263|559|
|Elapsed writer seconds|34.613|30.065|
|Achieved commits/sec|7.60|18.59|
|Commit p50 ms|25.07|3.76|
|Commit p99 ms|650.94|33.97|
|Visibility samples|21|29|
|Not visible within 3s|3|0|
|Completed-sample median visibility ms|1,304.93|460.00|
|Completed-sample maximum visibility ms|2,099.64|735.83|

A final blocked transaction can extend the baseline writer past its requested duration. The 30-second query window remains 30 seconds. The after run processed more than twice as many committed updates while query latency improved. Its two worker builds ran on `index-compactor`: read-only exports 81–85ms, builds 333–431ms, artifact persistence 131–152ms. No global revision-row lock covers these stages. Worker 0's recorded update-window Corpus wait p99 fell from 806.23ms to 73.05ms; catch-up still has some synchronization.

The 15-second after writer achieved 18.40 commits/sec (277 commits), commit p99 68.69ms; all 15 probes completed within three seconds, completed-sample maximum 959.91ms. Visibility samples include HTTP/network delay and are too few to establish a production p99/SLO. The requested 20 commits/sec was not fully achieved, even after optimization.

## Correctness and limitations

27 Java tests and five production-build Playwright tests passed, including paused-construction tests showing a separate writer completes before construction is released; concurrent deletion/context replay; obsolete candidate rejection after policy changes; bounded cache-write producer behavior; existing sharded ranking, safe restore, duplicate/gap recovery, tenant/RBAC/privacy and real JWT checks. A real fault probe compared six local/remote exact results, rejected unauthorized internal access and direct partial-worker serving, then paused/resumed an owned worker: the uncached coordinator request still failed closed with 503 in 192.06ms.

The host was not isolated, runs were not randomized or repeated for confidence intervals, and all changes were measured together. JFR adds overhead to both builds; do not treat the speedups as universal hardware-independent ratios. No 10K/100K-RPS or long endurance claim is made. Global revision serialization for actual mutations, full metadata replication, the 100K corpus cap, bounded-delta hard fallback, explicit synchronous rebuilds and finite history remain production constraints.
