> Latest verification: 27 Java tests and five production-build browser tests passed for background MVCC compaction, catch-up activation, bounded virtual-thread fan-out and deferred cache writes. The complete matched load matrix and 30-second compaction run passed all configured thresholds. See [the optimization report](performance-optimization-2026-11-17.md) for actual data and limits.

> Latest scaling implementation/evidence: [snapshot-plus-delta and HTTP shards](distributed-index.md), [measured load report](performance-2026-11-17.md). These supersede the earlier delta/sharding/load-test status below. The expanded suite now has 24 passing Java tests.

# Verification report — expanded workflows, 2026-11-17

The prioritized fuzzy-search, durable-snapshot/restore, analytics and admin workflows are implemented. See [contracts and trade-offs](workflows.md) for exact behavior and [baseline evidence](baseline-verification.md) for the original build.

## Executed checks

- Java 21 / Spring Boot 4.0.9 Maven verify: 13 tests, zero failures/errors/skips (5 Trie tests, 4 fuzzy/ranking tests, 3 live platform integration tests, 1 real RSA/JWKS test).
- Fuzzy insert/delete/substitute edits, Unicode, short-query and candidate budgets, unrelated-term rejection, and comparison against an independent brute-force reference.
- Real PostgreSQL 17 / Redis 7: Flyway V2–V4, atomic import, duplicate import rejection without partial writes, full and partial PATCH, deletion, tenant isolation, fuzzy API and highlight semantics, durable version history, fresh-instance recovery, safe rollback, corrupt and incompatible snapshot rejection, inactive/deleted-term suppression, 20-artifact retention, policy changes and restoration.
- Concurrent partial updates through two independent Corpus instances preserve both final fields. Sixteen concurrent duplicate events accept exactly one. Daily event quota, replay after quota, 30-day expiry, opt-out, trending, recent-history isolation and erasure are checked.
- Endpoint RBAC tests use a mocked decoder with distinct admin/reader/tenant/subject claims. Separate real signed-JWT tests validate expected issuer/audience, expiry, tampering and a valid token against a temporary JWKS endpoint.
- Five Chromium tests pass: real API suggestions and keyboard selection; delayed-response suppression; mobile viewport/Escape; complete admin controls using intercepted API contracts; opt-in analytics/selection/submission/erasure behavior. Admin browser tests are not claimed to exercise a real external identity provider; backend integration covers those transaction workflows.
- Next.js production build with TypeScript passes; the same five browser tests pass against standalone production startup in 5.4 seconds. No new performance claim is made; baseline JMH numbers have not been rerun for the expanded implementation.

## Reproduction

Start the dedicated demo PostgreSQL and Redis containers. From the project directory:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -f backend/pom.xml '-Dtest=*Test,*IT' verify
npm ci --prefix frontend
npm run build --prefix frontend
# Start the backend and frontend per README, then:
cd frontend
WEB_URL=http://127.0.0.1:3000 npx playwright test
```

PlatformIT targets localhost PostgreSQL 55432 and Redis 56379; it uses and cleans `test_*` tenants. JwtValidationIT starts a temporary loopback JWKS server. All tests need local socket/container permissions. CI runs the same suites with PostgreSQL/Redis service containers.

## Remaining scope and risks

The full master specification is not yet satisfied. Sharded serving, snapshot-plus-delta/outbox updates, compact/Radix comparison, hard end-to-end cancellation deadlines, OpenSearch adapter, full OpenAPI schema, 40-question/scenario documents, production load/SLO evidence, Testcontainers provisioning, full tracing/Grafana, Helm/Terraform and actual AWS deployment remain outstanding. The 100K local corpus limit and write-blocking full rebuild model remain; no 100M-term/100K-QPS claim is made.

Per-user private ACLs are unsupported; import tenant-public suggestions only. Analytics bot resistance is authenticated quota/deduplication, not a comprehensive anti-fraud system. A full OIDC login/token-refresh UX is not implemented; users supply short-lived access tokens. Metadata restoration preserves current eligibility/context and new IDs rather than recreating the complete historical corpus. Request-body size enforcement and distributed per-principal rate limiting still need production ingress/application work. Snapshot artifacts reside in PostgreSQL, not S3 or portable packed Trie files. Async streaming builds and audit actor/diff retention are not implemented.
