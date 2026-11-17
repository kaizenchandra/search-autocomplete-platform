# Operations, security, and AWS review

|Pillar|Implemented evidence|Remaining production work|
|---|---|---|
|Operational excellence|CI provisions DB/cache, unit/API/browser suites, version history, safe restore, scoped audit events|Hosted CI execution, tracing, release gates, incident exercises, audit actor/diffs|
|Security|JWT signature/issuer/audience/time validation, stateless admin RBAC, tenant/sub claims, consented HMAC-scoped history|TLS ingress, managed secrets, IAM, penetration tests, identity-aware distributed rate limits|
|Reliability|Atomic immutable publication, durable checked artifacts, 20-version retention, safe restore, freshness fail-closed, Redis fallback, PDB|RDS multi-AZ/PITR, backup restoration drills, AZ topology constraints|
|Performance efficiency|Bounded node Top-K, bounded DP fuzzy traversal, Redis cache, reference and concurrency tests|Production JMH/load/memory profiles, compact representation, delta indexing|
|Cost optimization|No mandatory Kafka/OpenSearch; explicit corpus/cache/artifact limits|Cost per million requests and per-term heap measurement|
|Sustainability|Client debounce/cancellation/cache, bounded traversal, avoidance of per-prefix corpus scans|Utilization measurements, idle scaling and incremental build optimization|

## Restore

Use Console version history or POST `/api/v1/admin/index/{version}/rollback`. Read [restore semantics](workflows.md) first: current eligibility and identity restrictions always win, deleted terms remain deleted, and restoration creates a new revision. A checksum/format mismatch returns 409 without advancing the corpus. Investigate storage corruption; do not disable validation. Keep independent database backups because the 20 retained artifacts are not a disaster-recovery backup strategy.

## Redis failure

Watch `autocomplete.cache.errors` and lookup latency. Search falls back to its immutable Trie. Restore Redis; cache entries expire without flushing shared infrastructure. Two sequential cache operations can each consume their configured 50ms timeout; the HTTP deadline is still a production gap.

## PostgreSQL failure

Serving fails closed after three seconds without authoritative revision confirmation. Restore PostgreSQL, verify the revision and counts, then polling rebuilds/publishes the corpus. Administrative writes require PostgreSQL. Snapshot artifacts also reside there; no offline artifact mode bypasses freshness checks.

## Memory pressure

Building holds the old and replacement nodes plus serialized metadata simultaneously. Enforce the 100K corpus cap; do not raise it without measuring peak memory. Context indexes and per-prefix Top-K references multiply storage. Prefer packed/Radix structures and shard snapshots when evidence justifies them.

## Identity and privacy

Configure JWK_SET_URI, OIDC_ISSUER, OIDC_AUDIENCE and ANALYTICS_SECRET through managed secret references. Administrative scope is autocomplete.admin; tenant and user subject claims are validated separately. No default admin password or production authentication bypass exists. Console/search tokens live only in page state. Analytics is authenticated and opt-in, capped/deduplicated transactionally; only approved IDs and event kinds are stored. Hourly retention handles the 30-day analytics/history policy. History erasure deletes per-actor data; disassociated aggregates remain. See [workflow privacy rules](workflows.md), including secret rotation limitations.

## Deployment

Compose is a local demo with explicitly local credentials. Kubernetes YAML deploys only the API; provision autocomplete-runtime Secret, database/cache networking, TLS, frontend deployment, registry and AZ placement separately. Cloud resources were not created and infrastructure manifests were not validated on a live cluster.

## Compaction and cache backpressure

`admin/index/status` reports `compacting`, `compactionFailure`, base revision and delta size. A rejected obsolete candidate preserves current state. The soft threshold is 256, hard bound 1,024; alert on persistent failure or growth toward the hard bound. Cache write saturation increments `autocomplete.cache.write.dropped`; `autocomplete.cache.write.queued` is capped at 256. Optional population can be dropped without changing correctness. See the JFR optimization report for the measured monitor/CPU bottlenecks and read-only export/activation contract.
