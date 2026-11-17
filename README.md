# Lumen — Search Autocomplete Platform

A working Java 21 / Spring Boot 4 baseline with a real Unicode code-point Trie, deterministic Top-K, PostgreSQL revisioned corpus, Redis ID caching, and a Next.js search UI. The prioritized fuzzy search, durable snapshot restore, analytics and administrative workflows are implemented; production scale targets still require the work listed in verification. See [verification](docs/verification.md) for evidence and gaps.

## Local demo

```sh
cd search-autocomplete-platform
docker compose up --build
# Open http://localhost:3000
curl 'http://localhost:8080/api/v1/autocomplete?prefix=spr'
```

PostgreSQL and Redis are published only on loopback ports 55432 and 56379. Compose contains explicitly local demo database credentials. Ten realistic seed terms are loaded by Flyway. For an external OIDC provider set JWK_SET_URI on the API. Public search needs no token.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -f backend/pom.xml verify
npm ci --prefix frontend
npm run build --prefix frontend
DATABASE_URL=jdbc:postgresql://localhost:55432/autocomplete REDIS_PORT=56379 java -jar backend/target/autocomplete-1.0.0.jar
# separate terminal
npm run dev --prefix frontend
k6 run -e RPS=1000 load-tests/autocomplete.js
```

Implemented endpoints: GET autocomplete (prefix, limit 1..20, category, language), trending (seven-day selection aggregates), recent (authenticated consented submissions), health; admin GET/POST suggestions, PATCH/DELETE suggestions/{id}, POST index/rebuild, GET index/status. PATCH accepts partial fields atomically. List pagination uses bounded offset, not cursor. Errors use ProblemDetail where handled. Snapshot version listing/restoration, atomic bulk import, ranking policy, analytics reports and interaction events are implemented. See [workflow contracts](docs/workflows.md). No claim of full OpenAPI coverage.

Source layout: backend/src/main/java/dev/typeahead/core contains framework-independent algorithms; other backend classes are application/adapters. frontend/app has Explore, Products, Console, and Operations routes; components contains the reusable combobox. Infrastructure, load scripts, and architecture/runbooks accompany them.

Read [architecture](docs/architecture.md) for complexity, consistency, alternatives and scale design; [operations](docs/operations.md) for the AWS six-pillar review, security limitations, and recovery procedures.

## Full verification and protected workflows

```sh
mvn -f backend/pom.xml '-Dtest=*Test,*IT' verify
```

Integration tests need the Compose PostgreSQL/Redis containers on 55432/56379. Configure `JWK_SET_URI`, `OIDC_ISSUER`, `OIDC_AUDIENCE`, and a minimum 32-character `ANALYTICS_SECRET` for protected workflows. Open Console to manage suggestions, import JSON, adjust ranking, rebuild, restore retained snapshots and inspect aggregates. Search privacy controls enable authenticated, consented selection/submission tracking, recent search retrieval and erasure.

## Sharded serving, incremental updates, and measurements

Ordinary updates now use a bounded snapshot-plus-delta overlay with transactional replay and tombstones. The service supports local shards or HTTP workers with version-safe global heap Top-K. See [implementation contracts](docs/distributed-index.md) and [actual load measurements](docs/performance-2026-11-17.md).

```sh
docker compose -f compose.yml -f compose.shards.yml up --build
# Reproducible load experiment: follow load-tests/README.md
```

The final focused 1,000-RPS coordinator cache-hit workload achieved 999.94 successful RPS and p99 17.29ms without errors; distributed cache misses and concurrent updates still failed important latency/error targets. No 100K-QPS claim is made.

## Cache-miss and compaction optimization

Read [the JFR-backed before/after report](docs/performance-optimization-2026-11-17.md). Matched distributed misses at 500 offered RPS improved from 3.28% errors / 151.67ms p99 to zero errors / 41.05ms p99. During the matched 30-second update run, query p99 fell from 290.01ms to 32.20ms, errors fell from 43.21% to zero, and committed updates rose from 7.60/sec to 18.59/sec. Every optimized matrix row passed its thresholds in these runs; production-scale guarantees remain unverified.
