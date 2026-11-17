# JFR profiling

`Stage` emits bounded stage names (Redis GET/SET, shard RPC, compaction export/build/persistence) with a 1ms threshold; it never records query text, IDs or access tokens. CPU samples and monitor events identify execution and waiting paths.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
python3 load-tests/serve.py --profile-dir profiling/run
# Separate terminal:
python3 load-tests/run.py --output load-tests/results/run --updates
# Stop serve.py to flush its recordings, then:
"$JAVA_HOME/bin/jfr" print --json --events autocomplete.Stage,jdk.ExecutionSample,jdk.JavaMonitorEnter profiling/run/worker0.jfr > /tmp/worker-profile.json
python3 profiling/analyze.py /tmp/worker-profile.json --runs load-tests/results/run/runs.json --output profiling/run-summary.json
```

The provided search.jfc uses the JDK profile settings while disabling startup environment/system-property/process/JVM-argument metadata. Measured before/after runs both used the standard profile settings; those local binary recordings were subsequently scrubbed of those startup metadata events. Scrubbing preserves the CPU/monitor/stage events used in analysis. Binary JFR files are local, ignored artifacts; summaries are retained in the repository. Thresholded stage/monitor summaries exclude faster events and must not be presented as whole-request percentiles. Workload windows come from run.py timestamps; update windows include writer/probe drain time after the fixed-duration query run.

To reproduce the additional compaction-threshold experiment:

```sh
python3 load-tests/run.py --roles coordinator --workloads cache-hit --duration 1s --high-rps 1 --update-duration 30 --output load-tests/results/compaction --updates
```

Reset the fixture and restart serving JVMs before each comparison. See docs/performance-optimization-2026-11-17.md for actual fingerprints, scope, failures and results.
