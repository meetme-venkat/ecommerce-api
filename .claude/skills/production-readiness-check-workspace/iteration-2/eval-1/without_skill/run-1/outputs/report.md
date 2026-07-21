# Pre-Launch Audit — ecommerce-api

**Verdict: NO-GO.** Do not deploy this to a public network as-is.

Two things will hurt you on day one, exactly the two you named:

1. **There is no authentication anywhere in the application.** `spring-boot-starter-security` is not on the classpath, there is no `SecurityFilterChain`, no `@PreAuthorize`, no API key filter. Every endpoint — including `GET /api/v1/customers`, which returns every customer name, email, phone and home address — is open to anyone who can reach the port.
2. **The stock check in `OrderService.create` is a read-then-write with no lock.** Two concurrent orders for the last unit both pass the check and both succeed. Stock goes negative and you sell inventory you do not have.

Everything else below is real but secondary to those two.

---

## Severity summary

| # | Issue | Severity |
|---|-------|----------|
| 1 | No authentication or authorization on any endpoint | **Critical** |
| 2 | Oversell race in order creation (unguarded read-modify-write on stock) | **Critical** |
| 3 | Customer PII fully exposed and fully mutable/deletable by anonymous callers | **Critical** |
| 4 | DB credentials hardcoded in committed `application.properties`; blank password | **High** |
| 5 | Lost-update race on cancel (double stock restore / double refund) | **High** |
| 6 | Caller supplies `customerId` — anyone can order or review as anyone | **High** |
| 7 | Unbounded `findAll()` on customers / products / orders — no pagination | **High** |
| 8 | N+1 query storms in `OrderService.findAll`, `ProductService.findAll`, `ReviewService.findByProduct` | **High** |
| 9 | No fallback exception handler — DB and parse errors surface as raw 500s | **Medium** |
| 10 | Deleting a referenced product/category/customer throws FK violation to 500 | **Medium** |
| 11 | Swagger UI and `/v3/api-docs` publicly served in production | **Medium** |
| 12 | `spring.jpa.show-sql=true` — logs every query, including PII, in production | **Medium** |
| 13 | Order number collision (8 hex chars) causing unique-constraint 500 | **Medium** |
| 14 | Payment never leaves `PENDING`; orders can reach DELIVERED unpaid | **Medium** |
| 15 | `db/schema.sql` starts with `DROP DATABASE IF EXISTS ecommerce_db` | **Medium** |
| 16 | Duplicate-check TOCTOU (email/SKU/slug) returns 500 instead of 409 under race | **Low** |
| 17 | No `CHECK (stock_quantity >= 0)` in schema — no last line of defence | **Low** |
| 18 | No controller/integration tests; no concurrency test | **Low** |


---

## Critical

### 1. Nothing is authenticated

`pom.xml` has web, data-jpa, validation, actuator, springdoc - and no security starter. There is no config class other than `OpenApiConfig`. So, for anyone who can reach the service:

- `GET /api/v1/customers` - dumps every customer record (`CustomerResponse` carries email, phone, address).
- `DELETE /api/v1/customers/{id}`, `DELETE /api/v1/products/{id}`, `DELETE /api/v1/categories/{id}` - anonymous destructive writes.
- `GET /api/v1/orders` - every order, every shipping address, every order total, for every customer.
- `PUT /api/v1/orders/{id}/status` - anyone can march anyone's order to DELIVERED, or cancel it.
- `PUT /api/v1/products/{id}` - anyone can reprice your catalogue.
- `/actuator/**` - unauthenticated. Boot defaults only expose `health` over HTTP, which limits the blast radius, but it is still unguarded and there is no `management.endpoints.*` config pinning that down.

This is the embarrassing-headline item. Nothing else on this list matters until there is an authenticated boundary, plus an authorization split between "customer acting on their own data" and "staff/admin".

Fix: add `spring-boot-starter-security`, define a `SecurityFilterChain` that denies by default, decide the auth mechanism (JWT resource server is the usual fit here), and make admin-only what is admin-only (product/category create-update-delete, customer list, order list, status transitions).

### 2. Oversell race in OrderService.create

In `src/main/java/com/venkat/ecommerce/api/service/OrderService.java`:

```java
Product product = productRepository.findById(itemRequest.getProductId())...
if (product.getStockQuantity() < itemRequest.getQuantity()) { throw new BusinessRuleException(...); }
product.setStockQuantity(product.getStockQuantity() - itemRequest.getQuantity());
```

`findById` takes no lock. `Product` has no `@Version`. The method is `@Transactional` at the default isolation (READ COMMITTED on Postgres), so two transactions both read stock=1, both pass the guard, both write stock=0, and the second write silently clobbers the first. You have sold two units of a one-unit item, and the "insufficient stock" check never fired.

This is not a rare interleaving - it is the normal case under a flash sale or a bot, which is exactly when it costs the most.

Fix, in order of preference:

- Atomic conditional decrement in the repository, treating "0 rows updated" as insufficient stock:

```java
@Modifying
@Query("update Product p set p.stockQuantity = p.stockQuantity - :qty " +
       "where p.id = :id and p.stockQuantity >= :qty")
int decrementStock(@Param("id") Long id, @Param("qty") int qty);
```

- Or `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a `findByIdForUpdate`, which also fixes the guard but serialises per product.
- Or `@Version` on `Product` plus retry on `OptimisticLockException` - cheapest to add, but the caller sees a conflict and must retry.

Also sort items by product id before locking, otherwise two multi-item orders touching the same two products in opposite order can deadlock.

Back it with a DB constraint: `ALTER TABLE products ADD CONSTRAINT chk_products_stock CHECK (stock_quantity >= 0);` so even a future bug cannot persist negative stock.

### 3. PII exposure

Covered by #1, but worth calling out separately because it is the disclosure risk rather than the integrity risk. `GET /api/v1/customers` with no auth and no pagination is a one-request customer-database export. If this service ever gets a public IP before #1 lands, treat that as a breach.

---

## High

### 4. Database credentials committed in application.properties

```
spring.datasource.url=jdbc:postgresql://localhost:5432/ecommerce_db
spring.datasource.username=postgres
spring.datasource.password=
```

Good news from checking history: no real password has ever been committed (`git log -S password` shows the value has always been blank). So there is nothing to rotate today. The problems are structural:

- Connecting as the `postgres` superuser. The app should have its own role with SELECT/INSERT/UPDATE/DELETE on its tables and nothing else.
- Blank password implies trust/peer auth on the DB, which will not survive contact with a real environment - and if someone fills the value in to make prod work, the secret lands in git.
- `localhost` is hardcoded; there is no `application-prod.properties` and no profile separation.

Fix: externalize to `${DB_URL}` / `${DB_USERNAME}` / `${DB_PASSWORD}` env vars (or a secret manager), add a dedicated non-superuser DB role, and add a prod profile. Never let a real value into the file.

### 5. Lost update on cancel

`cancel(Long id)` reads the order, checks the transition, restores stock item-by-item, and flips the payment to REFUNDED. Same read-then-write shape as #2 with no lock on the order row: two concurrent `POST /api/v1/orders/{id}/cancel` calls both see status PENDING, both pass the guard, and both add the quantities back. You end up with phantom inventory, and with COMPLETED-to-REFUNDED potentially triggered twice once a real payment gateway is wired in.

`updateStatus` has the same exposure - two concurrent transitions can both read the same `current` status.

Fix: load the order with PESSIMISTIC_WRITE, or add `@Version` to `Order`. The state machine in `ALLOWED_TRANSITIONS` is well-formed; it just is not enforced atomically.

### 6. customerId comes from the request body

`CreateOrderRequest.customerId` and `CreateReviewRequest.customerId` are client-supplied. Even after you add authentication, if these stay as-is an authenticated user can place orders and post reviews attributed to any other customer id. The identity must come from the security context, not the payload - drop the field from the request DTOs.

Related: reviews have no verified-purchase check and no one-review-per-customer-per-product constraint, so the review system is trivially spammable.

### 7. No pagination

`CustomerService.findAll`, `ProductService.findAll` and `OrderService.findAll` all call `repository.findAll()` and map the entire table into a response list. At 100k orders this is an OOM and a multi-second GC pause, triggerable by an anonymous GET. Switch to `Pageable` with a capped max page size.

### 8. N+1 queries

- `OrderService.findAll()` - 1 query for orders, then per order: lazy `items`, lazy `customer`, lazy `payment`, and per item a lazy `product` (touched in `toResponse` for `getName()`). For 100 orders averaging 3 items that is roughly 700 queries in one request.
- `ProductService.findAll()` - `toResponse` touches `product.getCategory().getName()`, and `category` is LAZY. One extra query per product.
- `ReviewService.findByProduct()` - `toResponse` touches `review.getCustomer().getFirstName()`. One extra query per review.

Fix: `@EntityGraph` or explicit `join fetch` on the repository methods (careful fetching two collections at once - fetch `items` in the query and let `@BatchSize` handle the rest), paired with #7.

---

## Medium

### 9. No fallback exception handler

`GlobalExceptionHandler` covers `ResourceNotFoundException`, `BusinessRuleException`, `DuplicateResourceException` and `MethodArgumentNotValidException`. Nothing else. So:

- `DataIntegrityViolationException` (FK violation, unique violation under race) becomes a default Spring 500.
- `HttpMessageNotReadableException` - e.g. a bogus enum value on the status endpoint or in `paymentMethod` - becomes a default 400 whose body leaks the Jackson parse message and class names.
- `MethodArgumentTypeMismatchException` - `/api/v1/products/abc` - is unhandled.

Add an `@ExceptionHandler(Exception.class)` that logs full detail server-side and returns a generic `ErrorResponse` with a correlation id, plus targeted handlers for the three above. Also pin `server.error.include-stacktrace=never` explicitly for prod.

Note: `AccessDeniedException` to 403 is documented in the project CLAUDE.md but there is no handler for it and no security layer to throw it. That lands with #1.

### 10. Deleting referenced rows

`ProductService.delete`, `CategoryService.delete` and `CustomerService.delete` call `repository.delete(entity)` with no check for dependent rows. A product that appears in any `order_items` row, a category with products, or a customer with orders will all blow up on the FK constraint and surface as an unhandled 500 (see #9). Product deletion is also the wrong operation for an e-commerce catalogue - you already have an `active` flag; soft-delete instead so order history stays intact.

### 11. API docs public

`springdoc-openapi-starter-webmvc-ui` is on the classpath with no configuration, so `/swagger-ui.html` and `/v3/api-docs` are live in every environment. That hands an attacker a complete map of your endpoints and schemas. Disable in prod (`springdoc.api-docs.enabled=false`) or put it behind auth.

### 12. show-sql=true in the shipped config

Every SQL statement is logged, with `format_sql=true`, in whatever environment this file is active. That is a performance tax and it puts query parameters - customer emails, addresses - into your logs. Turn it off outside dev.

### 13. Order number collisions

`Order.generateOrderNumber()` takes 8 hex chars of a UUID: about 4.3 billion values. By the birthday bound, collisions become likely in the tens of thousands of orders, and `order_number` is UNIQUE NOT NULL - so a collision is a constraint violation on save, surfacing as an unhandled 500 on a customer checkout. Use a DB sequence, or lengthen the random portion to at least 12-16 chars.

### 14. Payment is decorative

`create()` builds a `Payment` with `PaymentStatus.PENDING` and nothing ever moves it to COMPLETED. Meanwhile `ALLOWED_TRANSITIONS` happily walks an order PENDING -> CONFIRMED -> PROCESSING -> SHIPPED -> DELIVERED with the payment still pending. Nothing enforces "do not ship unpaid goods". Either gate the PENDING-to-CONFIRMED transition on payment completion, or be explicit that capture happens out-of-band - but it should be a deliberate decision, not an absence.

### 15. schema.sql opens with DROP DATABASE

`db/schema.sql` line 1 is `DROP DATABASE IF EXISTS ecommerce_db;` followed by re-creation and seed inserts. If that file is ever run against production - by a runbook step, a new hire, or a CI job with the wrong env var - it silently destroys everything. Split it into a destructive local-bootstrap script and a non-destructive migration path, and move schema management to Flyway or Liquibase before launch. (`ddl-auto=validate` is correct and should stay.)

---

## Low

### 16. Duplicate-check TOCTOU

`CustomerService.create` does `findByEmail(...).isPresent()` then `save(...)`; `ProductService` does the same on SKU, `CategoryService` on name and slug. Two concurrent requests both see "no duplicate" and the second gets a raw constraint violation, so a 500 instead of the intended 409. The DB constraints keep the data correct; only the error contract is wrong. Catch `DataIntegrityViolationException` and translate to `DuplicateResourceException`.

### 17. Missing stock CHECK constraint

Covered under #2 - worth doing regardless of which locking strategy you pick.

### 18. Test coverage gaps

Five service unit tests exist (CategoryServiceTest, CustomerServiceTest, OrderServiceTest, ProductServiceTest, ReviewServiceTest) plus the context-load test. There are no controller (`@WebMvcTest`) tests, no integration tests, and - most relevant here - no concurrency test that would have caught #2. Once the stock fix lands, add a test that fires N parallel orders at a product with N-1 units and asserts exactly one failure.

---

## What passed

Worth saying, because the fundamentals are solid and the fixes above are additive rather than a rewrite:

- DTO boundary is clean. Every one of the 16 controller methods returns a DTO or `ResponseEntity<Void>`. No entity is serialized to the wire, so no lazy-loading serialization blowups and no accidental field leakage.
- `@Transactional` coverage is complete. Every writing service method has it; every read-only method has `@Transactional(readOnly = true)`. Nothing is missing.
- Money is `BigDecimal` end to end - entities, DTOs, and NUMERIC(10,2)/NUMERIC(12,2) in the schema. No float anywhere.
- Bean validation is present on all request DTOs, including the `@Valid` cascade into `List<OrderItemRequest>` and `@Min(1)` on quantity, so zero and negative quantities are rejected at the edge.
- The order state machine is correct - `ALLOWED_TRANSITIONS` is explicit, terminal states are terminal, and cancellation routes through `cancel()` so the stock/refund side effects cannot be skipped. It just needs locking (#5).
- Schema quality is good: FKs on every relationship, unique constraints where they matter, CHECK constraints on quantity/price/rating, and sensible indexes on the FK and filter columns.
- Prices are snapshotted onto `OrderItem.unitPrice` at order time, so later catalogue repricing does not rewrite order history.
- No secrets have ever been committed - verified against full git history, not just the working tree.
- Lombok/DI conventions are consistent with the project standard: `@RequiredArgsConstructor` with final fields throughout, no `@Autowired`, no `@Data` on entities.

---

## Suggested order of work

Before any deploy:

1. Security layer - authenticate everything, then authorize (#1, #3), and drop client-supplied `customerId` (#6).
2. Atomic stock decrement + CHECK (stock_quantity >= 0) + a concurrency test (#2, #17, #18).
3. Locking or `@Version` on order cancel/status (#5).
4. Externalize DB credentials, non-superuser role, prod profile (#4).
5. Turn off show-sql and Swagger in prod (#11, #12).

Before real traffic:

6. Pagination + fetch joins (#7, #8).
7. Fallback exception handler and the three targeted handlers (#9, #16).
8. Soft-delete / referenced-row guards (#10).
9. Order number entropy (#13).
10. Migration tool; de-fang `schema.sql` (#15).

Decide explicitly:

11. Payment capture flow and whether it gates fulfilment (#14).

No code was changed - this is a read-only audit.
