# Production Hardening Audit — ecommerce-api

**Date:** 2026-07-20
**Scope:** Full read-only review of `src/main`, `pom.xml`, `application.properties`, `db/schema.sql`.
**Verdict: NO-GO for public release.**

There are 4 blocking issues (no authentication at all, PII exposed anonymously, an oversell race in order creation, and unhandled exception classes returning 500s with internal detail). The core domain modelling and layering are actually good — DTO boundary is clean, `@Transactional` coverage is correct, `BigDecimal` used throughout, DB constraints are real. The gaps are all in the operational/edge layer.

---

## Severity summary

| # | Severity | Area | Issue |
|---|---|---|---|
| 1 | **CRITICAL** | Auth | No Spring Security on the classpath — every endpoint is anonymous |
| 2 | **CRITICAL** | Data exposure | `GET /api/v1/customers` returns full PII for all customers, unauthenticated |
| 3 | **CRITICAL** | Concurrency | Oversell: stock read-modify-write with no lock or `@Version` |
| 4 | **CRITICAL** | Config | DB credentials hardcoded (empty password) in committed properties; no profiles, no env externalisation |
| 5 | **HIGH** | Error handling | No fallback `@ExceptionHandler` — unmapped exceptions return raw Spring error payload |
| 6 | **HIGH** | Error handling | `DataIntegrityViolationException` unhandled → duplicate/FK races surface as 500 |
| 7 | **HIGH** | Web layer | No pagination on any `findAll` — unbounded result sets |
| 8 | **HIGH** | Performance | N+1 queries in `OrderService.findAll`, `ProductService.findAll`, `ReviewService.findByProduct` |
| 9 | **HIGH** | Data integrity | Deletes ignore referential dependents (product in orders, customer with orders) |
| 10 | **HIGH** | Concurrency | Lost update on order status — no optimistic locking on `Order` |
| 11 | **MEDIUM** | Config | `spring.jpa.show-sql=true`; no pool sizing, no query/transaction timeouts |
| 12 | **MEDIUM** | Ops | Actuator on classpath with zero explicit endpoint/exposure config |
| 13 | **MEDIUM** | Web layer | Malformed enum / bad path-variable type produce unfriendly 400/500 with internal text |
| 14 | **MEDIUM** | Business logic | Payment can never reach `COMPLETED`; refund branch is dead code |
| 15 | **MEDIUM** | Validation | `price` accepts `0.00` and unbounded precision; `NUMERIC(10,2)` overflow → 500 |
| 16 | **MEDIUM** | Business logic | Reviews unrestricted: no purchase check, no one-per-customer-per-product constraint |
| 17 | **LOW** | Web layer | No CORS policy, no rate limiting, no request-size cap, no security headers |
| 18 | **LOW** | Transactions | `updateStatus` → `cancel` internal self-call bypasses the proxy |
| 19 | **LOW** | Ops | `db/schema.sql` opens with `DROP DATABASE IF EXISTS` |
| 20 | **LOW** | Tests | Service tests only — no `@WebMvcTest` controller or validation coverage |

---

## CRITICAL

### 1. No authentication or authorization anywhere
`pom.xml` has no `spring-boot-starter-security`; there is no `SecurityFilterChain`, no `@PreAuthorize`, no filter, no API key check. Every mapped endpoint is anonymous, including:

- `DELETE /api/v1/products/{id}`, `DELETE /api/v1/categories/{id}`, `DELETE /api/v1/customers/{id}`
- `PUT /api/v1/orders/{id}/status` — anyone can mark any order `DELIVERED`
- `POST /api/v1/orders/{id}/cancel` — anyone can cancel anyone's order, which restores stock and refunds
- `POST /api/v1/products` / `PUT` — anyone can reprice the catalogue

The project's own `CLAUDE.md` exception table lists `AccessDeniedException -> 403`, which implies an authorization layer was intended; it does not exist, and `GlobalExceptionHandler` has no handler for it either.

Files: `src/main/java/com/venkat/ecommerce/api/controller/*.java` (all five controllers).

### 2. Unauthenticated bulk PII export
`GET /api/v1/customers` (`CustomerController.findAll`) returns every customer with `email`, `phone`, and `address` populated from `CustomerResponse`. Combined with #1 this is an anonymous full customer-table dump. `OrderResponse` additionally exposes `shippingAddress` and `customerName` on `GET /api/v1/orders`.

Even after auth is added, `findAll` on customers should be admin-scoped and paginated, and `GET /orders/{id}` should be ownership-checked — right now any authenticated identity would still see every order.

Files: `.../controller/CustomerController.java`, `.../dto/CustomerResponse.java`

### 3. Oversell race in `OrderService.create`
```java
if (product.getStockQuantity() < itemRequest.getQuantity()) { throw ... }
product.setStockQuantity(product.getStockQuantity() - itemRequest.getQuantity());
```
This is a check-then-act on a row loaded with a plain `findById` (no lock), and `Product` has no `@Version` field. Two concurrent orders for the last unit both read `stockQuantity = 1`, both pass the check, both write `0`, and both commit. Stock goes negative under higher concurrency — and there is no DB `CHECK (stock_quantity >= 0)` in `schema.sql` to catch it either, so the invalid state persists silently.

The same pattern exists in `cancel()` on the restore path (`stock + quantity`), which can clobber a concurrent decrement.

Options, in order of preference: `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a `findByIdForUpdate` repository method; or `@Version` on `Product` plus retry; or an atomic conditional `UPDATE products SET stock_quantity = stock_quantity - :qty WHERE id = :id AND stock_quantity >= :qty` and treat 0 rows affected as insufficient stock. Add the `CHECK` constraint regardless as a backstop.

Secondary note: items are processed in whatever order the client sends them, so two orders touching the same two products in opposite order can deadlock once locking is introduced — sort items by `productId` before locking.

Files: `.../service/OrderService.java`, `.../entity/Product.java`, `db/schema.sql`

### 4. Credentials and environment config baked into the artifact
`src/main/resources/application.properties`:
```
spring.datasource.url=jdbc:postgresql://localhost:5432/ecommerce_db
spring.datasource.username=postgres
spring.datasource.password=
```
Committed to git, points at localhost, uses the superuser `postgres`, and has a blank password. There is a single properties file — no `application-prod.properties`, no profile activation, no `${DB_PASSWORD}` placeholders. Deploying this as-is either fails to connect or connects to something it should not.

Move all three to environment variables / a secret manager, add a `prod` profile, and use a non-superuser role scoped to `ecommerce_db`.

---

## HIGH

### 5. No fallback exception handler
`GlobalExceptionHandler` handles exactly four exception types. Anything else — `NullPointerException`, `DataAccessException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `HttpRequestMethodNotSupportedException` — falls through to Spring's default error handling, which does not produce the documented `ErrorResponse` shape and can echo internal messages to clients. Add an `@ExceptionHandler(Exception.class)` returning a generic 500 body while logging the stack trace server-side.

### 6. `DataIntegrityViolationException` unhandled -> 500 on every uniqueness race
All the duplicate checks are TOCTOU:
```java
if (customerRepository.findByEmail(request.getEmail()).isPresent()) { throw new DuplicateResourceException(...); }
```
The DB unique constraints (`uk_customers_email`, `uk_products_sku`, `uk_categories_name`, `uk_categories_slug`, `uk_orders_order_number`) are correctly in place and will catch the race — but the resulting `DataIntegrityViolationException` is unmapped, so a concurrent duplicate returns 500 instead of the documented 409. Same class of problem for FK violations from deletes (#9).

Also relevant: `Order.generateOrderNumber()` truncates a UUID to 8 hex chars. That is ~4.3B values, but with the birthday bound a collision becomes likely well before that; a collision today is an unhandled 500. Either widen it or catch and retry.

### 7. No pagination anywhere
`CategoryController.findAll`, `ProductController.findAll`, `CustomerController.findAll`, `OrderController.findAll`, and `ReviewController.findByProduct` all return unbounded `List<T>` backed by `repository.findAll()`. On a real catalogue or order table this is an OOM and a trivial DoS vector. Switch to `Pageable` / `Page<T>` with a default and maximum page size.

### 8. N+1 query patterns
- **`OrderService.findAll` — worst case.** `Order.customer` is `LAZY`, `Order.items` is `LAZY`, `OrderItem.product` is `LAZY`, `Order.payment` is `LAZY`. `toResponse` touches all four. For N orders averaging M items: 1 + N (customer) + N (items) + N*M (product) + N (payment) queries. 50 orders x 3 items is roughly 350 queries for one request.
- **`ProductService.findAll`** — `toResponse` reads `product.getCategory().getId()` and `.getName()`; `category` is `LAZY`, so 1 + N queries.
- **`ReviewService.findByProduct`** — `toResponse` reads `review.getCustomer()`; 1 + N queries.

Fix with `@EntityGraph` on the repository methods or explicit `join fetch` queries (for the order collection, fetch items+product in one query and customer/payment in a second to avoid a cartesian product).

### 9. Deletes ignore referential dependents
`ProductService.delete`, `CategoryService.delete`, and `CustomerService.delete` call `repository.delete(entity)` with no check for existing references. `order_items.product_id`, `products.category_id`, `orders.customer_id`, and `reviews.*` all have FKs, so the delete fails at the DB with a constraint violation -> unhandled -> 500 (see #6). Beyond the status code, hard-deleting a product that appears in historical orders is the wrong behaviour for an ecommerce system — prefer soft-delete (`active = false`, which `Product` already supports) and reject deletion when dependents exist with a 422.

### 10. Lost update on order status
`Order` has no `@Version`. `updateStatus` does read -> validate transition -> write with no locking, so two concurrent transitions from `PENDING` (say `CONFIRMED` and `CANCELLED`) can both pass validation and the last writer wins. In the cancel case that means stock is restored while the order is left `CONFIRMED`, or stock is restored twice. Add `@Version` to `Order` (and `Payment`) and map the resulting `OptimisticLockingFailureException` to 409.

---

## MEDIUM

### 11. Production-hostile datasource/JPA settings
- `spring.jpa.show-sql=true` and `hibernate.format_sql=true` — logs every statement, multi-line, on the hot path.
- No `spring.datasource.hikari.*` sizing, `connection-timeout`, `max-lifetime`, or `leak-detection-threshold`.
- No `spring.transaction.default-timeout`, no `jakarta.persistence.query.timeout`.
- `spring.jpa.open-in-view` is not set, so it defaults to `true`. Here every mapping happens inside `@Transactional` so it is not masking a `LazyInitializationException`, but it holds a connection for the whole request. Set it to `false`.
- `hibernate.dialect` is set explicitly, which is unnecessary with the Postgres driver present and only creates drift risk.
- `ddl-auto=validate` is correct — keep it.

### 12. Actuator unconfigured
`spring-boot-starter-actuator` is a dependency with no `management.endpoints.web.exposure.include`, no `management.endpoint.health.show-details`, no separate management port, and — given #1 — no security in front of it. Boot's defaults only expose `/health` over HTTP, so this is not currently a leak, but relying on the default is fragile. Pin the exposure list explicitly, set `show-details=when-authorized`, and add liveness/readiness probe configuration since none exists.

### 13. Malformed input produces poor responses
- `UpdateOrderStatusRequest.status` and `CreateOrderRequest.paymentMethod` are enums bound from JSON. An unknown value throws `HttpMessageNotReadableException` before validation runs — unhandled (#5), and Jackson's message enumerates the accepted constants along with class internals.
- `GET /api/v1/products/abc` throws `MethodArgumentTypeMismatchException` — also unhandled.
- `@Valid` on request bodies is applied consistently and correctly (including `@Valid` on the nested `items` list, which is good), but there is no `@Validated` on controllers, so any future `@Min` on a `@PathVariable`/`@RequestParam` would be silently ignored.

### 14. Payment lifecycle is incomplete
`OrderService.create` always writes `PaymentStatus.PENDING`, and no endpoint or service method ever sets `COMPLETED` or `FAILED`. Consequently the refund branch in `cancel()`:
```java
if (payment != null && payment.getPaymentStatus() == PaymentStatus.COMPLETED) { payment.setPaymentStatus(PaymentStatus.REFUNDED); }
```
is unreachable. `PaymentRepository` and `OrderItemRepository` are both declared but never injected anywhere. Also `OrderStatus.REFUNDED` is reachable from no transition in `ALLOWED_TRANSITIONS` — a defined-but-unreachable state. Either ship the payment capture flow or remove the dead states before release; a system where orders are created but never paid is a business-logic gap, not just dead code.

### 15. Price and amount validation gaps
- `ProductRequest.price` is `@DecimalMin("0.0")` inclusive — zero-price products are accepted. Likely should be exclusive.
- No `@Digits(integer=8, fraction=2)`. The column is `NUMERIC(10,2)`; a price of `99999999999.99` passes bean validation and fails at the DB -> unhandled 500. Same for a large order total against `NUMERIC(12,2)`.
- `OrderItemRequest.quantity` has `@Min(1)` but no `@Max` — a single item with `quantity = 2000000000` passes validation and is only stopped by the stock check.
- `CustomerRequest.address` and `ProductRequest.description` have no `@Size`; the columns are `TEXT`, so an unbounded body can be persisted.

### 16. Reviews are unconstrained
`ReviewService.create` validates only that the product and customer exist. There is no check that the customer actually purchased the product, and no unique constraint on `(product_id, customer_id)` in `schema.sql` — so a single customer can post unlimited reviews on any product. With #1 this is an anonymous ratings-manipulation endpoint. `ReviewService` also has no update or delete path, and reviews are never aggregated into a product rating.

---

## LOW / hygiene

### 17. Missing web-layer defence in depth
No CORS configuration (so browser clients are blocked, and the intended origins are undocumented), no rate limiting on any endpoint, no `server.tomcat.max-http-form-post-size` / `spring.servlet.multipart` limits, no HTTPS or HSTS / `X-Content-Type-Options` / frame-options headers (these would come free with Spring Security, see #1). Also no explicit `server.error.include-stacktrace=never`.

### 18. `updateStatus` calls `cancel` via `this`
```java
if (target == OrderStatus.CANCELLED) { return cancel(id); }
```
This is a self-invocation, so `cancel`'s own `@Transactional` is not applied — it works today only because the caller is already transactional. It also re-fetches the order and re-runs the transition check that was just performed. Extract the shared logic into a private method rather than relying on the ambient transaction.

### 19. `db/schema.sql` starts with `DROP DATABASE IF EXISTS ecommerce_db`
It also ends with `SELECT *` verification statements and includes seed customer rows with realistic-looking PII. This file is a local-dev bootstrap script, not a migration. Before release, adopt Flyway or Liquibase with versioned migrations (`ddl-auto=validate` already assumes schema is managed externally), and keep the destructive and seed portions out of anything a production pipeline can reach.

### 20. Test coverage gaps
`src/test` has five service unit tests and one context-load test. There are no `@WebMvcTest` controller tests, so status codes, validation responses, and the `GlobalExceptionHandler` mappings are entirely unverified — which matters given how many of the findings above are error-path issues. No concurrency test covers the oversell path in #3. Also, `spring-boot-starter-*-test` artifacts are listed individually rather than via `spring-boot-starter-test`, and there is no Testcontainers dependency, so nothing exercises real Postgres constraint behaviour.

---

## What is already correct

Worth stating so it does not get churned:

- Layering is clean — controllers are thin, no entity ever escapes a controller, every response is a DTO.
- `@Transactional` coverage is complete and correctly scoped: `readOnly = true` on all reads, write transactions on all mutations. No missing annotations found.
- All money fields are `BigDecimal` end-to-end (entities, DTOs, columns).
- Lombok conventions followed exactly (`@RequiredArgsConstructor` + `final`, no `@Autowired`, entities not using `@Data`).
- Enums persisted with `EnumType.STRING`, not ordinal.
- All associations are explicitly `FetchType.LAZY` — the N+1s in #8 are fetch-plan omissions, not eager-loading mistakes.
- Order status transitions are modelled as an explicit whitelist rather than ad-hoc `if` chains.
- `schema.sql` has real constraints: FKs, uniques, `CHECK` on quantity/price/rating, and sensible indexes on every FK and filter column.
- `ddl-auto=validate` — schema is not Hibernate-managed.
- Status codes match the project's API rules (201 on create, 204 on delete, 422 for business rules, 409 for duplicates).

---

## Suggested fix order

1. #1, #2 — add Spring Security, lock down write and PII endpoints. Nothing else matters until this is done.
2. #4 — externalise credentials, add a `prod` profile.
3. #3, #10 — locking on stock and order status; add the `stock_quantity >= 0` check constraint.
4. #5, #6, #13 — round out `GlobalExceptionHandler`.
5. #7, #8 — pagination and fetch plans (do together; pagination with `join fetch` on a collection needs care).
6. #9, #14, #15, #16 — data-integrity and business-rule gaps.
7. #11, #12, #17, #19, #20 — operational hygiene.
