# Pre-Release Hardening Report — ecommerce-api

Read-only review of the Spring Boot + JPA ecommerce API. Findings are grouped by severity.

**Scope:** configuration, transactions, the web layer, concurrency, persistence/N+1, validation, data-model correctness.

## Summary
Cleanly structured, good conventions (DTOs everywhere, centralized exception handling, ddl-auto=validate, BigDecimal money, order state machine, DB constraints/indexes). Release-blocking gaps cluster in three areas: concurrency (oversellable stock, racy duplicate checks, no @Version), web-layer robustness (unhandled exceptions -> 500s, no pagination, no security), and configuration (baked-in credentials/SQL logging, no prod profile, no pool tuning). Counts: 2 Critical, 5 High, 6 Medium, 4 Low.

## Critical

### C1. Stock can be oversold under concurrent orders (lost update)
OrderService.create() (lines 87-100) reads stock, checks it, then writes stock - qty with no locking. Two concurrent orders for the same product both read the same value, both pass the check, and the second write clobbers the first -> oversell. cancel() (165-168) restores stock the same unguarded way. There is no @Version column on any entity, no pessimistic lock (@Lock(PESSIMISTIC_WRITE)/SELECT FOR UPDATE), and no DB guard (no CHECK stock_quantity >= 0, no conditional UPDATE ... WHERE stock >= ?). Fix with any one of: version + retry, pessimistic lock, or atomic conditional update + CHECK constraint. Top pre-release risk.

### C2. No authentication or authorization on any endpoint
No Spring Security dependency, no security config. Every endpoint is open: customer PII (email/phone/address) readable via GET /api/v1/customers; anyone can create/cancel orders, drive status transitions, and DELETE products/categories/customers. Actuator is on the classpath with no exposure/access config. Critical if internet-facing; High if strictly internal — but make it a conscious decision.

## High

### H1. Duplicate-check races surface as 500, not 409
Customer/Category/Product create+update do findBy...isPresent() then save(). Concurrent requests both pass and both insert; the DB unique constraints (uk_customers_email, uk_products_sku, uk_categories_name/slug) prevent bad data but the resulting DataIntegrityViolationException is unhandled -> client gets 500 instead of the contracted 409. Add a handler and treat the constraint as source of truth.

### H2. Unhandled exception types bypass the error contract
GlobalExceptionHandler covers ResourceNotFound, BusinessRule, DuplicateResource, MethodArgumentNotValid. Missing: HttpMessageNotReadableException (malformed JSON / invalid enum for status/paymentMethod), MethodArgumentTypeMismatchException (e.g. GET /orders/abc), DataIntegrityViolationException (H1), and a catch-all Exception. These return Spring's default shape / 500 instead of ErrorResponse. CLAUDE.md also lists AccessDeniedException -> 403 with no handler.

### H3. findAll() endpoints are unbounded (no pagination)
Order/Product/Customer/Category controllers return full Lists. Loads every row (orders also trigger H4) per request — latency + memory/DoS risk. Use Pageable/Page with default and max page size.

### H4. N+1 queries on list/read paths
Lazy associations mapped correctly but toResponse walks them with no fetch join/@EntityGraph. OrderService.findAll(): per order getItems(), per item getProduct(), plus getCustomer() and getPayment() -> ~1 + N + N*items + N + N queries. ProductService.findAll(): getCategory() per product. ReviewService.findByProduct(): getCustomer() per review. Correct under readOnly tx but won't scale. Use @EntityGraph / JOIN FETCH (mind pagination + collection fetch interaction).

### H5. Unbounded string inputs (no @Size on TEXT-backed fields)
shippingAddress, notes, description, address, comment map to TEXT with no @Size cap — arbitrarily large payloads. ProductRequest.price lacks @Digits(integer=8, fraction=2) to match NUMERIC(10,2), so 1.234 or > 99,999,999.99 passes bean validation then fails at the DB as 500. Add @Size/@Digits bounds.

## Medium

### M1. spring.jpa.open-in-view left at default (true)
Not set -> Boot defaults to true and logs the warning. Mapping already happens inside transactional service methods, so OSIV isn't needed. Set open-in-view=false to keep DB sessions off the render path and make N+1 (H4) fail fast in tests.

### M2. DB credentials and SQL logging hard-coded
application.properties: password is empty, username is superuser postgres, both committed. Externalize to env/secrets and use a least-privilege role. show-sql=true + format_sql=true should be off in prod (log volume, latency, data leakage).

### M3. No production profile / no connection-pool tuning
Single application.properties with dev settings, no application-prod.*. HikariCP entirely on defaults. Size the pool against expected concurrency and Postgres max_connections; split dev/prod config.

### M4. Actuator exposed but unconfigured
Actuator dependency with no management.endpoints.web.exposure.* or access rules. Defaults are conservative (health/info) but with no security (C2) this is easy to widen accidentally. Pin exposure and protect management endpoints.

### M5. Payment lifecycle incomplete (dead refund branch)
Payments are created PENDING and nothing ever transitions to COMPLETED, so the refund branch in cancel() (172-174, if COMPLETED -> REFUNDED) is unreachable. Confirm whether a payment-capture flow is intended/missing before release.

### M6. updateStatus() -> cancel() self-invocation + redundant reload
updateStatus() (line 147) calls this.cancel(id). Both are @Transactional(REQUIRED) on the same bean and updateStatus already opened a tx, so it works — but it is a proxy self-invocation (inner @Transactional ignored) and re-runs getOrder(id) + the transition check. Refactor risk; extract a shared private helper.

## Low

### L1. Manual, inconsistent timestamp handling
createdAt/updatedAt set by hand with LocalDateTime.now(); Order uses @PrePersist only for order_number. Prefer @CreationTimestamp/@UpdateTimestamp or auditing. Customer has no updatedAt (matches schema).

### L2. Entity @Column mappings don't mirror DB precision/length
BigDecimal fields declare no precision/scale, relying on schema.sql NUMERIC(10,2)/(12,2). Safe under validate, but not self-documenting and fragile if ddl-auto changes.

### L3. schema.sql is destructive at the top
Opens with DROP DATABASE IF EXISTS ecommerce_db; plus psql \c/SELECT commands. Fine as a local bootstrap, but keep it off any app-managed migration path; consider Flyway/Liquibase for versioned migrations.

### L4. No CORS / request-size / rate-limit policy
No CorsConfigurationSource, no server.tomcat.max-* limits, no rate limiting. Deployment-dependent, but should be deliberate.

## What already looks good (leave alone)
- Every controller returns a DTO; no entity serialized out.
- Every write service method is @Transactional; reads are readOnly=true. No missing boundaries.
- ddl-auto=validate with explicit schema.sql (FKs, unique constraints, CHECKs, indexes present).
- Money is BigDecimal end to end, backed by NUMERIC.
- Order state machine with explicit allowed-transition map.
- Bean validation on request DTOs; centralized ErrorResponse.
- Defensive OrderItem subtotal recomputation via @PrePersist/@PreUpdate.

## Suggested fix order
1. C1 stock oversell. 2. C2/M4 security for API + actuator. 3. H1+H2 round out GlobalExceptionHandler. 4. H3+H4 pagination + N+1 fetch strategy. 5. H5 input-size/@Digits bounds. 6. M1/M2/M3 config. 7. Mediums/Lows as capacity allows.

Tell me which of these you want fixed and I'll implement them.
