# Verification report — 2026-11-17

## Executed and passed

- Java 21.0.13.0.3 Maven package and JUnit: five tests, zero failures/errors. Seeded reference comparison across 3,000 candidates, normalization and combining marks, supplementary code points, replacement-index deletion, 5,000 concurrent reads, bounds.
- Spring Boot 4.0.9 runtime started successfully. Resolved Spring JDBC 7.0.10, PostgreSQL driver 42.7.14; Jackson 3 JsonMapper serialized real API responses. Boot-managed security and Redis dependencies compile and start. No Spring Data JPA or Kafka is used.
- PostgreSQL 17 in Docker: Flyway applied V1 to an empty dedicated database. Ten seed terms loaded.
- Redis 7 local Docker; API uses real Redis and falls back on command exceptions. Versioned cache code exercised by repeat requests and deletion checks; outage/stampede tests remain unexecuted.
- Integration script: successful autocomplete, invalid limit 400, anonymous admin 401, cross-tenant exclusion, authoritative database update propagation, repeated cached lookup, deletion after revision change. This is a live-container script, not Testcontainers.
- Next.js 16.4.6 / React 19.3.1 production build and TypeScript passed. npm install audit reported zero vulnerabilities after upgrading initial dependencies.
- Three Chromium Playwright tests passed in 4.5 s: actual API keyboard selection, delayed older response suppression, mobile viewport and Escape. Mobile test does not exercise physical touch. Screen reader, axe and full WCAG validation remain unexecuted.
- JMH 1.37 ran with GC allocation profiling; [raw results and reproduction](benchmarks/README.md). Hot-prefix average 2.149 microseconds; rare-prefix average 0.288 microseconds. These are short exploratory microbenchmarks, not an API SLO.

## Implementation inventory

Implemented: code-point Trie construction, prefix traversal, immutable replacement deletion, precomputed Top-20, heap selection, normalized-text deduplication, deterministic ranking, context indexes, source-of-truth schema, revision polling and publication, version-scoped Redis ID cache, tenant-public search, JWT admin scope checks, validation, global token bucket, query highlighting, debounce/abort/sequence handling, search/product pages, minimal admin creation and index status page, container files, API Kubernetes reference, CI, k6 script, diagrams and six-pillar review.

Not implemented: mutable Trie insert/delete APIs, exact-terminal lookup API, Radix implementation or measured comparison, fuzzy matching, recency decay, pinned/blocked policies, sponsored labeling, private entity ACLs, bulk import, contextual POST, durable snapshot serialization, rollback/activation/version history, outbox/delta overlay, shard routing/merge, multi-region replication, analytics and real trending/recent search storage, full admin CRUD UI, charts, Tailwind/Zod/forms libraries, OpenAPI document, Testcontainers/property-testing libraries, security penetration suite, full 40-scenario and 40-question review, Grafana dashboards, OpenTelemetry tracing, Helm, Terraform, full AWS deployment and disaster recovery verification.

## Material risks

100K local corpus cap, full rebuild/write blocking, potentially large per-prefix metadata, no explicit HTTP cancellation deadline, two sequential Redis calls can consume 100ms during an outage, no cache stampede protection, process-local global rate limit, no production issuer/audience policy, no audit log, no persisted snapshot rollback. API readiness endpoint currently reports index status rather than enforcing the query freshness condition. Operations exposes aggregate counts across tenants. TLS and network restrictions must be supplied by deployment. Do not import private entities or sensitive queries.

A successful write waits for a rebuild on its serving replica. Other replicas poll every second, and queries fail closed when database revision confirmation is older than three seconds. This is an implemented local policy; failure tests across multiple replicas have not established a distributed SLO. Browser response cache lives at most 500ms. In-flight responses keep the version captured at request start. No 100-million-term, 100K-QPS, 99.99% availability, p99<50ms, or enterprise-readiness claim is made.

## Running verification

See README for builds and Compose. From the repository root, with a running API:

```sh
BASE_URL=http://127.0.0.1:8080 python3 search-autocomplete-platform/integration-tests/verify.py
cd search-autocomplete-platform/frontend
WEB_URL=http://localhost:3000 npx playwright test
```

The integration script temporarily adds and removes `integration-only` rows and increments the revision. Use only a disposable local database. This session used API port 33228 and UI port 33127 to avoid existing local services; database/cache use 55432/56379. Local services remain running for review. No cloud infrastructure was deployed.
