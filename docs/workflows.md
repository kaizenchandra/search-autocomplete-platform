> Update-model revision: [distributed-index.md](distributed-index.md) supersedes the full-rebuild-on-every-write description below. Durable history now checkpoints compactions; incremental revisions use the bounded overlay and replay log.

# Implemented workflows and contracts

## Fuzzy search

`GET /api/v1/autocomplete?prefix=sprong&fuzzy=true` first executes exact prefix lookup. Only an empty exact result activates approximate matching. The optional contextual POST accepts prefix, limit, category, language, and fuzzy fields with the same semantics.

The engine runs a Levenshtein dynamic-programming row along Unicode code-point Trie edges, pruning subtrees whose minimum row value exceeds one. An accepted approximate prefix contributes its node's bounded best candidates; descendants do not require full enumeration. Candidate deduplication uses normalized display text, and selection uses the active tenant ranking comparator. No full corpus scan or BK-tree is involved. Transpositions require two edits and are not specially supported.

Policy: normalized query 4..64 code points, one edit, at most 20,000 visited nodes, a soft 5ms CPU traversal deadline, and at most 20 retained candidates (temporary merge at most 40). Child and root loops stop when either budget expires. Complexity is O(visited nodes × query length), with depth at most query length+1. Java scheduling/GC and per-node work mean this is not a hard real-time guarantee. A budget-exhausted traversal may return an incomplete bounded result. Non-prefix fuzzy matches return empty highlight ranges rather than incorrect bold text. API exact-cache entries do not contain fuzzy results, so fuzzy-enabled requests cannot contaminate exact-only queries.

## Durable snapshots and restoration

PostgreSQL `index_snapshots` contains per-tenant canonical JSON metadata artifacts, format version 2, normalization version, SHA-256, term count, ranking weight, and creation time. Build order is tenant/ID deterministic. The format-2 checksum covers format, normalization policy, ranking weight, and the JSON payload. Legacy format-1 payload-only checksums remain readable for artifacts created before this upgrade. Java object serialization is intentionally avoided. Artifacts are committed before atomic publication; failed builds keep the prior active index. The latest 20 artifacts per tenant are retained. Version listing returns at most 100 rows, currently at most 20 retained. Startup rebuilds nodes from the authoritative PostgreSQL corpus; it does not blindly serve an artifact when authoritative freshness cannot be confirmed.

Restore/activation reads persisted artifacts, validates format/normalization/checksum/count and tenant IDs, applies changes transactionally under the revision-row lock, then rebuilds and publishes a new monotonically increasing revision. Cache keys therefore change even when restoring an old artifact. A fresh Corpus instance can recover from PostgreSQL without old process state.

**Safe restore semantics:** historical text, popularity, boost, and ranking weight are restored for existing active IDs. Current category, language, type, tenant and eligibility win. Deleted IDs are never reinserted; inactive IDs remain inactive; new current IDs remain present. This is intentionally a policy-aware metadata restore, not byte-for-byte resurrection of an old corpus. Both `/activate` and `/rollback` apply this contract. A missing artifact returns 404; integrity/format mismatch returns 409. Retention means versions older than the latest 20 cannot be restored through this API.

Compaction uses a consistent read-only export and background construction followed by replay/activation. It no longer holds the revision-row lock during construction or artifact persistence. Explicit administrative rebuilds remain synchronous on their local Corpus instance. Requests read safely published immutable nodes. Full snapshot serialization still consumes CPU/I/O for large corpora. The 100K corpus cap remains enforced transactionally for imports. This is not a replacement for a sharded snapshot-plus-delta architecture at production update rates.

## Administration

JWTs require a valid signature, expected issuer (`OIDC_ISSUER`), expected audience (`OIDC_AUDIENCE`), and non-expired timestamps. Administration requires `autocomplete.admin` scope plus a tenant claim. The service is stateless; it does not create browser sessions. User history/events additionally require a nonempty subject claim. Configure `JWK_SET_URI` for the actual issuer. Defaults point to the documented local realm; no admin credentials are bundled.

- GET/POST `/admin/suggestions`: bounded listing and create/upsert of a full validated term.
- PATCH/DELETE `/admin/suggestions/{id}`: partial field updates and scoped deletion. Identity is immutable; unknown fields are rejected. Read/merge/write occurs under the same DB transaction and revision lock, preventing lost unrelated-field updates across service instances.
- POST `/admin/suggestions/import`: 1..1000 full terms, unique IDs within the batch, same authenticated tenant, all-or-nothing validation and commit. An import cannot exceed the global local corpus cap.
- GET `/admin/index/status`: global revision with tenant-scoped term count.
- POST `/admin/index/rebuild`: synchronous rebuild; search readers retain the previous snapshot.
- GET `/admin/index/versions`, POST `/admin/index/{version}/activate` or `/rollback`.
- GET `/admin/ranking/policies`, PATCH `/admin/ranking/policies/default`: finite popularity weight 0..5. Score = weight × log1p(popularity) + boost. Precomputed node candidates and cache ordering are rebuilt under the new revision. Ties use normalized text and ID.
- GET `/admin/analytics/queries` and `/zero-results`: up to 100 aggregates; no raw queries or user identifiers.

The browser console supports listing/pagination, create, edit, active toggle, delete, JSON import, ranking changes, rebuild, version listing/restore, index status and analytics. Access tokens stay in component memory. API authorization is independent of browser inputs. Minimal audit records store tenant, operation class and timestamp; actor identity and before/after audit diffs remain future work.

## Analytics and recent search privacy

POST `/autocomplete/events` accepts `{intentId, kind, termId, consent}`. Kind is one of ISSUED, SHOWN, SELECTED, SUBMITTED, SUCCEEDED, ZERO_RESULTS, ABANDONED. Raw query text, IP addresses, conversion data and arbitrary metadata are not accepted or stored. Selected/shown/succeeded events require an active approved suggestion ID from the authenticated tenant. Events without consent do not write data. Browser integration currently emits explicit SELECTED and SUBMITTED events only; it does not infer success/conversion or track each keystroke.

A user/tenant HMAC-SHA256 (using `ANALYTICS_SECRET`, minimum 32 characters) scopes deduplication. The secret is mandatory for authenticated analytics/history; without it these features return 503. Public autocomplete remains available. The intent ID remains stable while typing a single intent and changes on a subsequent search. Actor/intent/kind is unique. PostgreSQL advisory transaction locks make quota checks and deduplication consistent across instances. A daily maximum of 100 accepted events per actor limits inflation; duplicate replay remains a no-op even after reaching the quota. This is basic authenticated abuse control, not Sybil/bot-proof popularity measurement.

Daily aggregate counts keep event kinds distinct. Trending ranks approved active IDs by SELECTED counts over today plus the previous six UTC/database days. It does not overwrite imported popularity or business metrics. Queries/zero-result reports cover the 30-day window. No impression CTR or inferred conversion score is applied.

Consented SUBMITTED events maintain at most 20 recent suggestion IDs per tenant/actor. GET `/autocomplete/recent` resolves only currently active authorized tenant terms. DELETE `/autocomplete/events` atomically erases the actor's recent IDs and dedup records under the same actor lock used by ingestion. The browser also disables consent after successful erasure. Existing non-identifying aggregate totals remain; future explicitly consented events may be accepted again. This is not an account-wide persistent opt-out preference service.

Hourly maintenance deletes aggregates/dedup records and recent entries older than the 30-day policy. Expired recent data is excluded immediately from reads; physical cleanup may lag by up to one maintenance interval. Dedup subjects remain pseudonymous, not anonymous: protect the HMAC secret, database, and backups. Secret rotation invalidates access to history stored under the prior key; a production rotation/migration procedure is still needed.

## Deployment and testing

Compose includes PostgreSQL/Redis and the application. The analytics secret default is explicitly local-demo-only; override it and OIDC settings for any shared deployment. CI provisions dedicated PostgreSQL and Redis service containers and invokes both unit and integration suites, builds Next.js, installs Chromium and runs browser tests. The workflow was edited locally; GitHub-hosted execution has not been observed.

Integration tests use a real PostgreSQL/Redis pair and a test-only mocked JwtDecoder for endpoint RBAC scenarios. A separate RSA/JWKS integration test verifies actual signature, issuer, audience, expiry and tamper rejection. No production authentication bypass was added. Integration suites deliberately use isolated `test_*` tenants and clean their data.
