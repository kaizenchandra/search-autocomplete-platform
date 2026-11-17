> Historical pre-optimization microbenchmark. These JMH numbers were not rerun for the current score/normalization caches; current measured evidence is in [the optimization report](../performance-optimization-2026-11-17.md).

# Exploratory local JMH result

Java 21.0.13.0.3, macOS arm64, JMH 1.37, one fork, 512 MiB heap, one 1-second warmup and two 1-second measurement iterations. Corpus: 10,000 synthetic `product N` terms. Average time, not p99. Concurrent services were running. No confidence interval is meaningful from this short run.

|Operation|µs/op|Allocated B/op|
|---|---:|---:|
|Hot `pro` prefix / K=10|2.149|1304.015|
|Rare `product 9999` / K=10|0.288|1384.002|
|NFKC normalization|0.227|1192.002|

The allocation figures show optimization opportunities; this is not a low-allocation implementation. No Radix comparison, memory-per-term measurement, API load test, Zipf workload, cold rebuild measurement, virtual/platform thread comparison, or production capacity result was executed. Full raw result: jmh-local.json.

Reproduce from project directory:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -f backend/pom.xml test dependency:build-classpath -Dmdep.outputFile=/tmp/autocomplete-classpath
"$JAVA_HOME/bin/java" -cp "backend/target/test-classes:backend/target/classes:$(cat /tmp/autocomplete-classpath)" org.openjdk.jmh.Main IndexBenchmark -wi 1 -i 2 -w 1s -r 1s -f 1 -prof gc -jvmArgs '-Xmx512m'
```

Use substantially longer warmup/measurement and multiple forks for decisions. Network, Redis, filtering, authentication, and HTTP costs are excluded.
