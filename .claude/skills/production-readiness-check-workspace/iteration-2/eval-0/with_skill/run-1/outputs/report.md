# Production Readiness Report

**Verdict: NOT READY — 7 critical, 11 warning, 5 info**
The API has no authentication layer of any kind, so every endpoint — including `GET /api/v1/customers` (full PII dump) and `DELETE /api/v1/customers/{id}` — is anonymously callable; fix that before anything else on this list.

## 🔴 Critical

- **[auth]** No security layer exists at all. No `spring-boot-starter-security` dependency, no `SecurityFilterChain`, no JWT filter, no `@PreAuthorize`/`@Secured` anywhere in the source tree — every one of the 19 endpoints is anonymous — [`pom.xml:32-86`](pom.xml#L32)
  Fix: add `spring-boot-starter-security` with a deny-by-default `SecurityFilterChain`, opt only `GET /api/v1/products/**` and `GET /api/v1/categories/**` into `permitAll()`. This is a design change, not a patch — plan it, don't bolt it on.

- **[auth]** `GET /api/v1/customers` returns every customer's first name, last name, email, phone and postal address, unauthenticated and unpaginated. This is a one-request bulk PII exfiltration endpoint — [`CustomerController.java:30`](src/main/java/com/venkat/ecommerce/api/controller/CustomerController.java#L30), fields at [`CustomerResponse.java:16-22`](src/main/java/com/venkat/ecommerce/api/dto/CustomerResponse.java#L16)
  Fix: require authentication and an admin role; for self-service, return only the authenticated principal's own record.

- **[auth]** Anonymous destructive mutations on enumerable integer IDs: `DELETE /api/v1/customers/{id}` and `DELETE /api/v1/products/{id}` — [`CustomerController.java:51`](src/main/java/com/venkat/ecommerce/api/controller/CustomerController.java#L51), [`ProductController.java:51`](src/main/java/com/venkat/ecommerce/api/controller/ProductController.java#L51)
  Fix: gate all `POST`/`PUT`/`DELETE` behind authentication + role check.

- **[auth]** IDOR — the server trusts a client-supplied `customerId` to decide who is placing an order. Any caller can create an order billed and shipped in another customer's name — [`CreateOrderRequest.java:22`](src/main/java/com/venkat/ecommerce/api/dto/CreateOrderRequest.java#L22), consumed at [`OrderService.java:71`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L71)
  Fix: derive the customer from the `Authentication` principal and drop `customerId` from the request DTO.

- **[auth]** IDOR — same pattern on reviews: `customerId` comes from the request body, so anyone can post a review under any customer's name — [`CreateReviewRequest.java:18`](src/main/java/com/venkat/ecommerce/api/dto/CreateReviewRequest.java#L18), consumed at [`ReviewService.java:31`](src/main/java/com/venkat/ecommerce/api/service/ReviewService.java#L31)
  Fix: resolve the reviewer from the authenticated principal.

- **[config]** Blank database password committed in the only properties file. The production DB would be protected by network reachability alone — [`application.properties:5`](src/main/resources/application.properties#L5)
  Fix: `spring.datasource.password=${DB_PASSWORD}` sourced from the environment or a secrets manager, and set a real password on the `postgres` role. Note the username `postgres` (line 4) is the DB superuser — the app should run as a least-privilege role.

- **[concurrency]** Oversell race on order creation. Stock is read, compared, then decremented in application memory with no lock and no atomic DB update. Two concurrent orders for the last unit both pass the check and both commit — stock goes negative and you ship inventory you don't have — [`OrderService.java:93-100`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L93)
  Fix: make the decrement atomic — `UPDATE products SET stock_quantity = stock_quantity - :qty WHERE id = :id AND stock_quantity >= :qty` and reject when rows-affected is 0. Alternatively `@Lock(PESSIMISTIC_WRITE)` on the product lookup.

- **[concurrency]** Double-cancel race inflates stock. `cancel()` checks the current status, then adds every item's quantity back to product stock. Two concurrent `POST /orders/{id}/cancel` calls both read `PENDING`, both pass the transition check, and both restore stock — phantom inventory from a single order — [`OrderService.java:158-168`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L158)
  Fix: lock the order row on load (`@Lock(PESSIMISTIC_WRITE)`) or add `@Version` to `Order` so the second commit fails; guard the status transition with a conditional `UPDATE ... WHERE status = :expected`.

## 🟠 Warning

- **[n+1]** `GET /api/v1/orders` is a four-way N+1. `findAll()` loads orders, then per order the mapper touches lazy `items` (line 187), each item's lazy `product` name (line 191), the lazy `customer` (line 212), and `payment` — which is an eager-by-necessity `@OneToOne(mappedBy)` and fires its own SELECT per order. 100 orders x 3 items ~= 1 + 100 (items) + 300 (products) + 100 (customer) + 100 (payment) ~= 600 queries — [`OrderService.java:59`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L59), mapper at [`OrderService.java:187-212`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L187)
  Fix: add a repository query `select distinct o from Order o left join fetch o.items i left join fetch i.product left join fetch o.customer left join fetch o.payment` (only one collection may be join-fetched — `items` — the rest are to-one and safe).

- **[n+1]** `GET /api/v1/products` fires one extra SELECT per product for the category name. `getCategory().getId()` on line 105 is free (FK on the proxy); line 106 `getCategory().getName()` is the query — [`ProductService.java:27`](src/main/java/com/venkat/ecommerce/api/service/ProductService.java#L27), triggered at [`ProductService.java:106`](src/main/java/com/venkat/ecommerce/api/service/ProductService.java#L106)
  Fix: `@EntityGraph(attributePaths = "category")` on a `findAll` override, or a `join fetch` query.

- **[n+1]** `GET /api/v1/products/{id}/reviews` fires one SELECT per review for the reviewer's name — unbounded, since review counts grow without limit — [`ReviewService.java:50`](src/main/java/com/venkat/ecommerce/api/service/ReviewService.java#L50), triggered at [`ReviewService.java:61`](src/main/java/com/venkat/ecommerce/api/service/ReviewService.java#L61)
  Fix: `@EntityGraph(attributePaths = "customer")` on `findByProductIdOrderByCreatedAtDesc`.

- **[validation]** Every collection endpoint returns an unbounded `List<DTO>` straight from `findAll()` with no `Pageable`: customers, products, orders, categories, reviews. Fine at 50 rows, an OOM and multi-second response at 500,000 — and it degrades silently rather than failing a test — [`ProductController.java:30`](src/main/java/com/venkat/ecommerce/api/controller/ProductController.java#L30), [`OrderController.java:29`](src/main/java/com/venkat/ecommerce/api/controller/OrderController.java#L29), [`CustomerController.java:30`](src/main/java/com/venkat/ecommerce/api/controller/CustomerController.java#L30), [`CategoryController.java:30`](src/main/java/com/venkat/ecommerce/api/controller/CategoryController.java#L30), [`ReviewController.java:27`](src/main/java/com/venkat/ecommerce/api/controller/ReviewController.java#L27)
  Fix: accept `Pageable` and return `Page<DTO>` with a capped default page size.

- **[validation]** `items` on the order request has `@NotEmpty` but no upper bound. A single request with 100,000 line items costs the client nothing and runs 100,000 product lookups server-side — [`CreateOrderRequest.java:32-34`](src/main/java/com/venkat/ecommerce/api/dto/CreateOrderRequest.java#L32)
  Fix: `@Size(max = 100)` on the list, plus `@Max` on `OrderItemRequest.quantity`.

- **[validation]** Free-text fields mapped to unbounded `TEXT` columns have no length cap: `shippingAddress` and `notes` — [`CreateOrderRequest.java:25`](src/main/java/com/venkat/ecommerce/api/dto/CreateOrderRequest.java#L25), `comment` — [`CreateReviewRequest.java:25`](src/main/java/com/venkat/ecommerce/api/dto/CreateReviewRequest.java#L25), `description` — [`ProductRequest.java:25`](src/main/java/com/venkat/ecommerce/api/dto/ProductRequest.java#L25) and [`CategoryRequest.java:24`](src/main/java/com/venkat/ecommerce/api/dto/CategoryRequest.java#L24), `address` — [`CustomerRequest.java:33`](src/main/java/com/venkat/ecommerce/api/dto/CustomerRequest.java#L33)
  Fix: add `@Size(max = ...)` to each.

- **[error-handling]** No fallback `@ExceptionHandler(Exception.class)` and no handler for `DataIntegrityViolationException`. Any unanticipated failure escapes the advice and returns Spring's default error body, breaking the `ErrorResponse` contract the rest of the API promises — [`GlobalExceptionHandler.java:16-47`](src/main/java/com/venkat/ecommerce/api/exception/GlobalExceptionHandler.java#L16)
  Fix: add a catch-all handler returning a generic 500 `ErrorResponse` (log the cause, never return it), plus a `DataIntegrityViolationException` -> 409 handler.

- **[error-handling]** Concurrent duplicate creates surface as an opaque 500, not 409. `CategoryService.create` / `CustomerService.create` / `ProductService.create` do a `findByX().isPresent()` check then `save()`; the DB unique constraint correctly rejects the loser, but the resulting `DataIntegrityViolationException` is unhandled — [`CustomerService.java:36-47`](src/main/java/com/venkat/ecommerce/api/service/CustomerService.java#L36), [`CategoryService.java:36-50`](src/main/java/com/venkat/ecommerce/api/service/CategoryService.java#L36), [`ProductService.java:39-55`](src/main/java/com/venkat/ecommerce/api/service/ProductService.java#L39)
  Fix: handle `DataIntegrityViolationException` -> 409 `DuplicateResourceException` semantics (the DB constraints themselves are correct — see Passed).

- **[data-integrity]** The `stock_quantity >= 0` invariant is enforced only in Java. `products` has no `CHECK` constraint, so any path that bypasses the service check (including the race above) writes a negative stock silently — [`db/schema.sql:39`](db/schema.sql#L39)
  Fix: `ALTER TABLE products ADD CONSTRAINT chk_products_stock CHECK (stock_quantity >= 0);` — note `order_items` and `payments` already do this correctly.

- **[concurrency]** No `@Version` on `Product` or `Order` — the two most contended entities in the system. Concurrent updates last-write-wins with no detection — [`Product.java:28`](src/main/java/com/venkat/ecommerce/api/entity/Product.java#L28), [`Order.java:37`](src/main/java/com/venkat/ecommerce/api/entity/Order.java#L37)
  Fix: add a `@Version private Long version;` field plus the matching column, and handle `OptimisticLockException` with a retry or a 409.

- **[config]** `show-sql=true` and `format_sql=true` with no profile separation — every query goes to production logs at volume, with parameter-bearing SQL — [`application.properties:8-9`](src/main/resources/application.properties#L8)
  Fix: move both to an `application-dev.properties` and leave them off by default.

## 🔵 Info

- **[auth]** Swagger UI / OpenAPI (`springdoc-openapi-starter-webmvc-ui`) is on the runtime classpath with no exposure restriction, so `/swagger-ui.html` and `/v3/api-docs` publish the full endpoint map in prod — [`pom.xml:50-54`](pom.xml#L50), [`OpenApiConfig.java:11`](src/main/java/com/venkat/ecommerce/api/config/OpenApiConfig.java#L11)
  Fix: gate the springdoc paths behind auth, or disable with `springdoc.api-docs.enabled=false` in the prod profile. (Info rather than Warning only because it exposes nothing the missing-auth criticals don't already expose.)

- **[transactions]** Self-invocation: `updateStatus` calls `cancel(id)` through `this` at line 147, bypassing the proxy. Harmless today — the caller's own `@Transactional` is already active, so the work still commits atomically — but `cancel`'s propagation settings are silently ignored and this would break if anyone changed them — [`OrderService.java:147`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L147)
  Fix: extract the shared cancellation logic into a private helper both public methods call, so no `@Transactional` boundary is crossed via `this`.

- **[dto]** `OrderStatus`, `PaymentMethod` and `PaymentStatus` live in the `entity` package and are referenced by response/request DTOs. They are `public enum`, not `@Entity`, so nothing leaks — but it couples the wire contract to the persistence package — [`OrderResponse.java:3`](src/main/java/com/venkat/ecommerce/api/dto/OrderResponse.java#L3), [`OrderStatus.java:3`](src/main/java/com/venkat/ecommerce/api/entity/OrderStatus.java#L3)
  Fix: optional — move the enums to a shared `domain`/`enums` package.

- **[data-integrity]** No DB migration tool (Flyway/Liquibase). Schema is a hand-run `db/schema.sql` that begins with `DROP DATABASE IF EXISTS ecommerce_db;` — there is no versioned, repeatable path to apply a schema change to a live prod database, and the file itself is dangerous to run by hand — [`db/schema.sql:1`](db/schema.sql#L1)
  Fix: adopt Flyway, baseline the current schema as `V1__init.sql`, and strip the `DROP DATABASE` from anything runnable against prod.

- **[data-integrity]** `reviews.customer_id` has a foreign key but no index, unlike every other FK in the schema — [`db/schema.sql:172`](db/schema.sql#L172)
  Fix: `CREATE INDEX idx_reviews_customer ON reviews (customer_id);`

## ✅ Passed

- **config**: `spring.jpa.hibernate.ddl-auto=validate` — the correct production setting; no risk of Hibernate mutating the schema on boot. No API keys, JWT secrets or tokens found anywhere in config.
- **transactions**: every write path is annotated. All five services carry `@Transactional` on creates/updates/deletes and `@Transactional(readOnly = true)` on queries — including the dirty-checking updates in `CustomerService.update` and `ProductService.update` that write without an explicit `save()`.
- **dto**: no boundary leakage. All 19 controller methods return a DTO, `List<DTO>`, or `ResponseEntity<Void>` on DELETE (a correct 204); no `@Entity` type is returned or bound as a `@RequestBody`; no DTO embeds an entity.
- **validation**: every `@RequestBody` parameter carries `@Valid`, and `CreateOrderRequest.items` correctly cascades with `@Valid` on the list. Request DTO constraints are genuinely thorough — `@Email` on email, `@Min(1)`/`@Max(5)` on rating, `@DecimalMin`/`@PositiveOrZero` on price and stock, `@Size` caps on all the VARCHAR-backed strings.
- **error-handling**: `GlobalExceptionHandler` maps all four domain exceptions to the documented statuses (404/422/409) and returns field-level validation errors as 400. No stack traces or raw exception text reach clients; `server.error.include-stacktrace` is not set to `always`.
- **auth**: `GET /api/v1/products` and `GET /api/v1/categories` are legitimately public catalogue reads — correctly unauthenticated, not a finding.
- **money**: all monetary fields are `BigDecimal` end to end (entities, DTOs, `NUMERIC(10,2)`/`NUMERIC(12,2)` columns) — no float drift.
- **data-integrity**: unique constraints exist on `categories.name`, `categories.slug`, `products.sku`, `customers.email`, `orders.order_number` and `payments.order_id`, so the check-then-insert races have a working DB backstop. `CHECK` constraints correctly guard order item quantity/price and review rating (1-5).
- **n+1**: `findById` paths are clean — single-aggregate fetches inside an open transaction, bounded work, not a per-row explosion.

---

Want me to fix these? I'd start with the criticals. Two are mechanical and safe: the blank DB password (externalize to `${DB_PASSWORD}` — but the password must be **set and rotated** on the DB side; a blank value means the credential is effectively already public) and the stock-decrement race (swap to an atomic conditional `UPDATE` plus a `CHECK` constraint). The auth layer is the opposite — introducing security touches every endpoint and every client, so I'd want to agree the shape (JWT vs session, role model, which routes stay public) before writing a line of it.
