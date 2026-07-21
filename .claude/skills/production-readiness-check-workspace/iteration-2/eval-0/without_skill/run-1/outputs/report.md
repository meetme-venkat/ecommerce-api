# ecommerce-api — Production Readiness Audit

**Date:** 2026-07-20 · **Scope:** full repo, read-only · **Build:** `./mvnw test` passes (23 unit tests + `contextLoads`, all green)

## VERDICT: NO-GO

Not a maybe. The API has **zero authentication** — every endpoint is anonymous, including `GET /api/v1/customers`, which returns full PII for every customer (name, email, phone, home address) to anyone who can reach the port, and `DELETE /api/v1/products/{id}`, which lets an anonymous caller destroy the catalogue. On top of that, order placement has a textbook oversell race with no DB safety net. The code itself is good; everything *around* it — security, config, concurrency, operations — is missing.

## Blockers

**B1. No authN/authZ anywhere — CRITICAL.** No `spring-boot-starter-security` in `pom.xml`, no `SecurityFilterChain`, no `@PreAuthorize`. All 19 endpoints anonymous. Exploitable today: bulk PII dump via `GET /api/v1/customers`; `PUT /api/v1/customers/{id}` lets anyone rewrite anyone's email/address (delivery-hijack); anonymous `DELETE` on products/categories; anyone can mark any order DELIVERED or cancel any customer's order; `POST /api/v1/orders` places orders on behalf of any `customerId` named in the body — there is no concept of an authenticated user; open review spam.

**B2. Oversell / negative-stock race in `OrderService.create` — CRITICAL.** `src/main/java/com/venkat/ecommerce/api/service/OrderService.java` does read-check-write on stock with no lock at READ COMMITTED (confirmed in boot log): `if (product.getStockQuantity() < qty) throw; product.setStockQuantity(stock - qty);`. No `@Version` on `Product`, no `PESSIMISTIC_WRITE`, no conditional UPDATE. And **no DB safety net** — `products.stock_quantity` in `db/schema.sql` has no `CHECK (stock_quantity >= 0)`, so negative stock persists. Same pattern in `cancel()` when restoring stock.

**B3. Config/credentials not production-ready — CRITICAL.** `application.properties` is committed with hardcoded `localhost:5432`, superuser `postgres`, and a **blank password**. No `prod` profile at all (boot log: "No active profile set"). `show-sql=true` + `format_sql=true` logs every statement, including customer data, in prod.

**B4. `db/schema.sql` is a loaded gun — CRITICAL.** Opens with `DROP DATABASE IF EXISTS ecommerce_db;`, ends with demo seed data. No Flyway/Liquibase — schema changes are a human running this file by hand.

**B5. Unhandled exceptions leak 500s — HIGH.** `GlobalExceptionHandler` covers 4 exceptions with **no catch-all `Exception` handler** and no `DataIntegrityViolationException` handler. `DELETE /api/v1/customers/{id}` on a customer with orders hits `fk_orders_customer` → raw 500 instead of 409 + `ErrorResponse`; same for category-with-products and product-in-order-items. Also unmapped: `HttpMessageNotReadableException` (bad enum) and `MethodArgumentTypeMismatchException`. The project's own rules require `AccessDeniedException → 403`, unimplemented because there's no security.

## High severity

- **H1** No pagination — all four list endpoints `findAll()` the entire table.
- **H2** N+1 explosion on `GET /api/v1/orders`: per order, items + product-per-item + payment + customer. 100 orders × 3 items ≈ 601 queries. Same shape in `ProductService.findAll` and `ReviewService.findByProduct`.
- **H3** `spring.jpa.open-in-view` left enabled (startup warning) — masks H2, holds a connection per request.
- **H4** Payments never actually taken: `Payment` created `PENDING`, nothing ever sets `COMPLETED`, `PaymentRepository` injected nowhere. Orders can reach DELIVERED unpaid; the refund branch in `cancel()` is dead code and `OrderStatus.REFUNDED` is unreachable.
- **H5** No `@Version` on any entity — concurrent product/customer updates silently lose writes.
- **H6** No review integrity: no purchase verification, no duplicate prevention.
- **H7** Swagger (`/v3/api-docs`, `/swagger-ui.html`) and `/actuator/health` publicly readable, unauthenticated.

## Medium

No CORS config; no rate limiting and unbounded order `items` list; `price` allows 0.0 with no scale bound vs `NUMERIC(10,2)`; no slug format validation; unbounded TEXT fields with no `@Size`; no `reviews.customer_id` index; hard deletes despite `Product.active`; `CustomerService.update` writes no timestamp (`Customer` has no `updatedAt`); no graceful shutdown or Hikari tuning; redundant `hibernate.dialect` (deprecation warning each boot).

## Test gaps

23 service-layer Mockito tests, all passing. **Zero controller tests** (no `@WebMvcTest`) — every status code and the entire `GlobalExceptionHandler` contract is unverified. **Zero integration tests** — `EcommerceApiApplicationTests` is a bare `contextLoads` hitting a real local PostgreSQL (log: `Database version: 18.4`), so the build is not reproducible on clean CI. No concurrency test for B2, no repository tests.

## What's good

Clean three-layer separation, thin controllers. **Every controller method returns a DTO** — no entity reaches the wire. `@Transactional` correct on all writes, `readOnly=true` on all reads. `BigDecimal` end to end. Order transitions are a real state machine with terminal states, and cancellation routes through `cancel()` so side effects can't be bypassed. Lombok conventions match project rules exactly. Schema has FKs, uniques, CHECKs on quantity/price/rating, covering indexes. Bean Validation on every request DTO with `@Valid` cascading into order items.

## Minimum path to GO

1. Spring Security, deny-by-default, `customerId` from the principal (B1). 2. Atomic stock decrement + `CHECK (stock_quantity >= 0)` (B2). 3. Env-var config, `prod` profile, `show-sql=false`, Swagger off (B3, H7). 4. Flyway; no `DROP DATABASE` in any prod-reachable path (B4). 5. Complete `GlobalExceptionHandler` incl. catch-all (B5). 6. Pagination + `JOIN FETCH`/`@EntityGraph` + `open-in-view=false` (H1–H3). 7. Resolve payment capture or block SHIPPED on unpaid orders (H4). 8. `@WebMvcTest` + Testcontainers so CI can verify 1–7.

Items 1–5 are non-negotiable and exceed one working day plus review — the second reason Friday is a no-go.
