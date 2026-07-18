# Pre-Launch Readiness Audit — ecommerce-api

**Verdict: NOT READY to go live.** Two blocking issues match exactly what you were worried about — a database with no password, and an order flow that oversells stock under concurrent traffic. There's also a bigger structural gap: the API has no authentication at all, so anyone who can reach it can read every customer's personal data and delete records. Fix the Critical items before launch.

Scope: read-only inspection of the Spring Boot source, JPA entities, config, and SQL schema. Nothing was modified or run.

## Critical — fix before launch

### C1. Database has a blank password
`src/main/resources/application.properties:3-5` — `spring.datasource.username=postgres`, `spring.datasource.password=` (empty). The `postgres` superuser has no password, so the DB is protected only by network reachability. It's the "leaked password" problem in its worst form: nothing to leak because there is no password.
**Fix:** set a strong password, use a least-privilege app role (not `postgres`), and externalize it as `spring.datasource.password=${DB_PASSWORD}` via env var / secrets manager. If a real credential ever lands in this file it must be **rotated**, not just deleted, since it stays in git history.

### C2. Stock oversell race condition
`src/main/java/com/venkat/ecommerce/api/service/OrderService.java:93-100` — reads `getStockQuantity()`, checks it, then decrements, with no atomicity guard. `Product` has no `@Version` (`entity/Product.java`), `ProductRepository` has no pessimistic `@Lock` (`repository/ProductRepository.java`), and the DB column has no `CHECK (stock_quantity >= 0)` (`db/schema.sql:35-52`). Under READ_COMMITTED, two concurrent orders for the last unit both pass the check and both decrement — overselling, possibly to negative stock.
**Fix:** add `@Version` optimistic locking (+ retry), or a `PESSIMISTIC_WRITE` lock on the product read; add a DB `CHECK (stock_quantity >= 0)` as a backstop.

### C3. No authentication or authorization anywhere
No `spring-boot-starter-security` in `pom.xml`, no security config. Every endpoint is public: `GET /api/v1/customers` returns all customers with email/phone/address (`CustomerController.java:29-32`, `CustomerResponse.java`) — a PII leak; customer/product delete endpoints are open; `POST /api/v1/orders` trusts a client-supplied `customerId` (`CreateOrderRequest.java:21-22`) → IDOR, order/cancel as anyone; same for reviews (`ReviewService.java:31-32`).
**Fix:** add Spring Security, require auth on state-changing and PII endpoints, and derive the acting customer from the authenticated principal, not the request body.

## Warnings
- **W1. `show-sql=true` / `format_sql=true`** in prod (`application.properties:8-9`) — noisy/slow, may log data. Disable outside dev. (`ddl-auto=validate` on line 7 is the correct, safe choice — good.)
- **W2. N+1 queries** on `OrderService.findAll` (`:57-62`, lazy walk of items→product/customer/payment) and `ProductService.findAll` (`:26-30`, lazy `category` per product). Use `JOIN FETCH`/entity graphs and paginate.
- **W3. Reviews** have no verified-purchase check and no dedup (`ReviewService.create:27-43`) — fake-review/spam vector, amplified by C3.
- **W4. Actuator** is on the classpath with default exposure but unsecured because of C3. Lock it down / bind to an internal port.

## Passed — verified clean
- DTO boundary clean: every controller returns a `*Response` DTO, never a JPA entity.
- All DB-writing service methods are `@Transactional` (the oversell is a locking gap, not a missing-transaction gap).
- Request bodies validated (`@Valid` + `@NotNull/@NotBlank/@Min/@Email/@DecimalMin/@PositiveOrZero`); negative price/quantity rejected.
- `GlobalExceptionHandler` returns structured errors with no stack traces.
- Money uses `BigDecimal` throughout.
- No hardcoded secrets in source (only benign Maven-wrapper `MVNW_PASSWORD` references).

## Suggested fix order
1. C1 (externalize/rotate DB password) → 2. C2 (atomic stock decrement) → 3. C3 (auth + principal-derived customer) → then W1–W4.

---

_Note: the harness blocked writing the report to the requested path during the run, so this file was reconstructed verbatim from the agent's returned output._
