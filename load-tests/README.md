# Reproducible API load tests

Prerequisites: Java 21, Maven, Python 3, Docker Compose and k6. Load fixtures are synthetic. Use a disposable local database; `seed.sql` owns/reset IDs `loadbench-0..19999` in the public tenant/category `load`. It does not replace unrelated suggestions. The global 100K corpus cap is checked.

From the project directory:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
docker compose up -d postgres redis
mvn -f backend/pom.xml '-Dtest=*Test,*IT' verify
cat load-tests/seed.sql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U autocomplete -d autocomplete
python3 load-tests/serve.py
# Separate terminal; serves until Ctrl-C or the 900-second lifetime expires:
python3 load-tests/run.py --output load-tests/results/local-run --updates
python3 load-tests/report.py load-tests/results/local-run
```

`serve.py` refuses occupied ports, starts two independent worker JVMs (33241/33242), a metadata-only coordinator (33240) and an all-local-shards comparison JVM (33243). It uses two shards, 512MiB maximum heap per JVM, a shared dedicated PostgreSQL/Redis pair (55432/56379), and a test-only process limit of 10,000 RPS/burst 10,000. Ctrl-C terminates only the processes it created. The application default remains 200 RPS/burst 500. Use private loopback only; the benchmark internal credential is explicitly development-only.

The runner executes 10-second constant-arrival-rate runs at 500 RPS for cache-hit, distinct-prefix cache-miss, short-prefix and fuzzy workloads in both modes; then 1,000-RPS cache-hit runs and an optional 15-second 500-RPS mixed workload with concurrent updates. Two prefix requests warm each run; there is no exhaustive JIT warmup. Runs are sequential and share Redis. Miss offsets/limits avoid reusing the prior run's keys. Redis hit/miss deltas include the two warmup requests and, during updates, freshness probes. Fuzzy rows describe exact-cache negative hits; fuzzy candidates themselves are recomputed/fanned out.

`updates.py` requests 20 updates/sec over 128 IDs. It waits for PostgreSQL to acknowledge each committed revision and reports achieved commits/sec rather than enqueued SQL. At most one sample/sec probes the coordinator until the new score appears or three seconds elapse; sampled visibility includes client/network delay. Transaction latency is not index freshness. The updater follows the exact revision-row-lock / suggestion change / revision increment / COMMIT-marker contract used by the application. It is a local benchmark driver, not a public unauthenticated update API.

Raw JSON/logs, k6 exit codes, server metric deltas, sampled process RSS and environment/artifact fingerprints are retained. `report.py` reports HTTP and successful RPS separately, successful-response p50/p95/p99, HTTP errors and dropped iterations. A nonzero threshold exit is preserved, not silently ignored. Sampled RSS is not heap size or true peak memory; `%cpu` from ps is a process lifetime value, not instantaneous utilization. This harness does not establish GC p99 or memory per term.

For individual scale targets, including 10K/50K/100K offered RPS:

```sh
BASE_URL=http://127.0.0.1:33240 WORKLOAD=cache-miss RPS=10000 DURATION=30s VUS=300 MAX_VUS=1000 k6 run --summary-export results/10k.json autocomplete.js
```

From `load-tests/`, change RPS as needed. Above the configured service limit, 429s are expected. At high offered rates a local generator may drop iterations; those are not achieved server throughput. A distributed generator and larger isolated deployment are required before testing a 100K-QPS service target. No such throughput is claimed.

## Recorded runs

The first result set under `results/2026-11-17` is retained even though several thresholds failed. It exposed update-time revision mismatch and a cold-miss burst. The subsequent result set is generated from a separately fingerprinted build after bounded revision history/on-demand catch-up and single-flight coalescing. See the measured report linked from the project README for comparison and remaining failures. These are short single-host observations; workload order, JIT/GC, other host activity, database/cache scheduling and statistical noise are confounders.

For the final focused confirmation (cache-hit at 500/1,000 RPS plus updates):

```sh
python3 load-tests/run.py --roles coordinator --workloads cache-hit --output load-tests/results/focused --updates
```

To keep correctness checks separate from the large benchmark fixture, create a disposable verification database and set `TEST_DATABASE_URL=jdbc:postgresql://localhost:55432/autocomplete_verify` when invoking Maven. The final correctness suite has 24 tests. The optional `fault_probe.py` compares local/remote results and briefly pauses/resumes only a validated owned benchmark worker; do not point it at shared or production processes.

## Profile and compaction comparisons

Use `serve.py --profile-dir profiling/run` for JFR capture. Both original before/after experiments used the same standard JFR profile; the supplied reproduction configuration additionally suppresses startup metadata. See profiling/README.md for filtered analysis and `--update-duration 30` for a matched run that crosses the compaction trigger. All measured baseline failures remain under results/optimization-before and results/optimization-compaction-before.
