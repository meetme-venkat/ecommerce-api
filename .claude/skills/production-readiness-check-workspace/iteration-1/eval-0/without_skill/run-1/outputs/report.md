# Production Readiness Review — ecommerce-api

## Verdict: NO-GO for Friday

Do not ship this to production on Friday. This is a clean, well-structured codebase, but it is missing several things that are non-negotiable for a public-facing ecommerce API. At least four of them are hard blockers, and any one of them alone is enough to hold the release. The good news: none are deep design flaws, and the fixes are well-understood.

---

## Blockers (must fix before any prod deploy)

### 1. There is no authentication or authorization — every endpoint is public
`pom.xml` pulls in web, JPA, validation, actuator, and springdoc — but **not** `spring-boot-starter-security`. There is no security config anywhere (`config/` contains only `OpenApiConfig`). That means, once deployed, anyone on the network can:

- `DELETE /api/v1/products/{id}` and `DELETE /api/v1/categories/{id}` — delete your catalog.
- `GET /api/v1/customers` — read every customer's name, email, phone, and address (PII exposure / likely a compliance violation).
- `POST /api/v1/orders`, cancel orders, mutate order status — for any customer.

For an ecommerce API this is a categorical no-go. Needs authentication (API keys / OAuth2 / JWT) and role-based authorization on mutating and PII endpoints before it faces prod traffic.

### 2. Stock can be oversold under concurrent orders (race condition)
`OrderService.create()` (src/main/java/.../service/OrderService.java:87-100) does a read-modify-write on `product.stockQuantity`:

```java
if (product.getStockQuantity() < itemRequest.getQuantity()) { throw ... }
product.setStockQuantity(product.getStockQuantity() - itemRequest.getQuantity());
```

There is no optimistic locking (`Product` has no `@Version` field), no pessimistic lock, and no atomic DB-side decrement. Two orders placed at the same time for the last unit will both pass the check and both succeed — you oversell. On an ecommerce launch this *will* happen. Fix with a `@Version` column, a `SELECT ... FOR UPDATE`, or a conditional `UPDATE ... WHERE stock_quantity >= ?`.

### 3. N+1 queries + unbounded result sets on the list endpoints
All the list reads will fall over as data grows because every relationship is `LAZY` and the `toResponse` mappers walk them:

- `OrderService.findAll()` → `toResponse()` touches `order.getItems()`, each `item.getProduct()`, `order.getCustomer()`, and `order.getPayment()` — all lazy. One query to load orders, then several per order. `OrderRepository` has no `JOIN FETCH`.
- `ProductService.findAll()` → `toResponse()` touches `product.getCategory()` (lazy) — N+1.
- `ReviewService.findByProduct()` → `toResponse()` touches `review.getCustomer()` / `getProduct()` — N+1.

Compounding it: **none of the `findAll` endpoints are paginated** — `GET /api/v1/orders` and `GET /api/v1/products` return the entire table every call. With real production volume this is both a latency and a memory problem. Fix with `JOIN FETCH` / entity graphs and `Pageable`.

### 4. Production configuration is not production-ready
`src/main/resources/application.properties`:

- Datasource is hardcoded to `jdbc:postgresql://localhost:5432/ecommerce_db` with `username=postgres` and an **empty password** — no externalized config, no prod profile (`application-prod.properties` does not exist), no secret management.
- `spring.jpa.show-sql=true` — every SQL statement logged in prod: noise, overhead, and potential data leakage in logs.
- No connection-pool sizing, timeouts, or other prod tuning.

Externalize DB/credentials via environment variables or a secrets store, add a prod profile, and turn off SQL logging before shipping.

---

## Major issues (should fix before, or immediately after with a plan)

- **No database migration tooling.** No Flyway/Liquibase. `spring.jpa.hibernate.ddl-auto=validate` is the right choice, but the schema is applied by hand from `db/schema.sql` — and that file begins with `DROP DATABASE IF EXISTS ecommerce_db;`. If that script is ever pointed at prod, it wipes everything. There is no versioned, forward-only migration path. This is a serious operational hazard for a Friday deploy.
- **Exception handling has gaps that produce 500s + stack traces.** `GlobalExceptionHandler` covers the custom exceptions and validation, but has no fallback for generic `Exception` or for `DataIntegrityViolationException`. Concurrent duplicate inserts (e.g. two customers with the same email, or an `order_number` collision) will race past the app-level checks and surface as raw 500s. Add a catch-all and a data-integrity handler.
- **Actuator is on the classpath with no security.** With no security starter, management endpoints have no protection layer. Lock down / restrict actuator exposure explicitly.

---

## Minor / follow-ups (not blockers)

- `order_number` is generated from 8 hex chars of a UUID (`Order.generateOrderNumber()`), on a `UNIQUE` column with no retry — a collision (unlikely but possible at scale) becomes a 500 with no recovery.
- Test coverage is service-layer unit tests only. No web-layer (`MockMvc`) or persistence (`@DataJpaTest`) tests, so entity/schema drift and controller wiring are unverified. Build/test pass status was not run as part of this read-only review.
- Payments are always created `PENDING` with no real processing — confirm this stub is intended for launch.
- No CORS configuration — confirm whether a browser client needs it.

---

## What's genuinely good

- Consistent layering (controller -> service -> repository), constructor injection, `final` fields.
- **DTO coverage is clean** — every `@RestController` method returns a DTO / `List<DTO>` / `ResponseEntity<DTO>`; no entities leak across the API boundary.
- **`@Transactional` coverage is correct** — every DB-writing service method is transactional; reads use `readOnly = true`.
- Money is `BigDecimal` throughout; DB uses `NUMERIC`.
- Solid DB constraints (FKs, unique keys, check constraints) and sensible indexes in `schema.sql`.
- Order-status transitions are modeled explicitly and validated; cancellation correctly restores stock and refunds.

---

## Bottom line
**NO-GO.** Ship-blockers are: (1) no authN/authZ on any endpoint, (2) oversell race in order creation, (3) N+1 + unpaginated list endpoints, (4) non-production config with a plaintext empty DB password and SQL logging on. Add to that the lack of migration tooling and a destructive `schema.sql`. Close the four blockers and put a real migration path in place, then re-review — the underlying code quality is good, so this is achievable, but not by Friday without cutting corners you shouldn't cut on a payments-adjacent system.
