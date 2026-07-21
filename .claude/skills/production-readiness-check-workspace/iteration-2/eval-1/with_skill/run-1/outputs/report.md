# Production Readiness Report

**Verdict: NOT READY — 6 critical, 11 warning, 6 info**
The API has no authentication layer of any kind — `GET /api/v1/customers` is an anonymous bulk dump of every customer's name, email, phone and address, and anyone can `DELETE` any product or customer. Fix auth before anything else; the stock-oversell race is the close second.

## 🔴 Critical

- **[auth]** No security layer exists at all. `pom.xml` has no `spring-boot-starter-security` (or equivalent), and there is no `SecurityFilterChain`, auth filter, or any `@PreAuthorize`/`@Secured` anywhere in `src/main/java`. Every one of the 21 endpoints is anonymous. — `pom.xml:32`
  Fix: add `spring-boot-starter-security` with a `SecurityFilterChain` that is `denyAll` by default, then `permitAll()` only the public catalogue reads (`GET /api/v1/products`, `GET /api/v1/categories`, `GET /api/v1/products/{id}/reviews`). This is a design change — plan it, don't bolt it on.

- **[auth]** Bulk customer PII on an open endpoint. `GET /api/v1/customers` returns every customer's `firstName`, `lastName`, `email`, `phone`, and `address` with no authentication, no paging, and no filtering. One unauthenticated curl exfiltrates the entire customer table. — `controller/CustomerController.java:30`
  Fix: require authentication on `/api/v1/customers/**`; restrict the collection endpoint to an admin role and `GET /{id}` to the owning principal.

- **[auth]** Anonymous destructive mutations. `DELETE /api/v1/products/{id}`, `DELETE /api/v1/customers/{id}` and `DELETE /api/v1/categories/{id}` take a sequential `Long` id anyone can enumerate, with no auth check. So do the `POST`/`PUT` create/update paths on all three. — `controller/ProductController.java:51`, `controller/CustomerController.java:51`, `controller/CategoryController.java:51`
  Fix: gate all non-`GET` catalogue routes behind an admin role; gate customer mutations behind authentication + ownership.

- **[auth]** Client-supplied identity (IDOR) on order creation. `CreateOrderRequest.customerId` is taken straight from the request body and trusted — `OrderService.create` loads that customer and books the order against them. Any caller can place orders, with a shipping address of their choosing, on behalf of any customer id. — `dto/CreateOrderRequest.java:22`, `service/OrderService.java:71`
  Fix: drop `customerId` from the request DTO and derive the customer from the authenticated `Authentication` principal.

- **[auth]** Same IDOR on reviews — `CreateReviewRequest.customerId` is trusted, so anyone can post reviews attributed to any customer (and the response echoes that customer's real name). — `dto/CreateReviewRequest.java:18`, `service/ReviewService.java:31`
  Fix: derive the reviewer from the principal; remove the field from the DTO.

- **[concurrency]** Stock oversell race in order creation. Classic read-check-write with no lock and no atomic DB update: the check on line 93 and the decrement on line 100 are not atomic across transactions. Two concurrent orders for the last unit both read `stockQuantity = 1`, both pass, both write `0` — one unit sold twice. With enough concurrency stock goes negative. — `service/OrderService.java:93`
  Fix: make the decrement atomic in the DB — `@Modifying @Query("update Product p set p.stockQuantity = p.stockQuantity - :qty where p.id = :id and p.stockQuantity >= :qty")` and treat a 0-rows-affected result as insufficient stock. Alternative: `@Lock(PESSIMISTIC_WRITE)` on the product fetch. Back it with a DB `CHECK (stock_quantity >= 0)` either way.

## 🟠 Warning

- **[config]** Blank database password committed. `spring.datasource.password=` with `username=postgres` means the DB is protected only by network reachability, and the credential shape is checked into git. — `src/main/resources/application.properties:5`
  Fix: `spring.datasource.password=${DB_PASSWORD}` and `username=${DB_USER}`, supplied by the environment/secrets manager. Set a real password on the `postgres` role before deploying. (Nothing here needs *rotating* — no live secret was leaked, only an empty one.)

- **[concurrency]** Lost-update race on stock restore during cancellation. `cancel()` reads `product.getStockQuantity()` and writes back a computed sum with no lock — two concurrent cancels touching the same product, or a cancel racing an order, can lose one of the increments. — `service/OrderService.java:167`
  Fix: same atomic `UPDATE … SET stock_quantity = stock_quantity + :qty` treatment as the decrement, or add `@Version` to `Product` and retry on conflict.

- **[data-integrity]** No DB backstop for the stock invariant. `products.stock_quantity` is `INTEGER NOT NULL DEFAULT 0` with no `CHECK (stock_quantity >= 0)`, so the "never oversell" rule lives only in Java. Note the schema *does* have CHECK constraints on `order_items` and `payments` — this one looks like an oversight. — `db/schema.sql:39`
  Fix: `ALTER TABLE products ADD CONSTRAINT chk_products_stock CHECK (stock_quantity >= 0);`

- **[n+1]** N+1 explosion on `GET /api/v1/orders`. `findAll()` loads N orders, then `toResponse` touches four lazy paths per order: `order.getItems()` (line 187), `item.getProduct().getName()` (line 191), `order.getPayment()` (line 198), `order.getCustomer().getFirstName()` (line 212). For 100 orders averaging 3 items that's roughly 1 + 100 (items) + 300 (products) + 100 (payments) + 100 (customers) ≈ 600 queries per request. — `service/OrderService.java:187`
  Fix: add a repository query with `select distinct o from Order o join fetch o.customer left join fetch o.items i join fetch i.product left join fetch o.payment` — only one collection (`items`) may be join-fetched, so this is safe. Pair it with paging.

- **[n+1]** N+1 on `GET /api/v1/products`. Every response row calls `product.getCategory().getName()` on a `LAZY @ManyToOne` — one extra SELECT per product. (Line 105's `getCategory().getId()` is free; line 106 is what fires the query.) — `service/ProductService.java:106`
  Fix: `@EntityGraph(attributePaths = "category")` on a `findAll` override, or a `join fetch` query.

- **[n+1]** N+1 on `GET /api/v1/products/{id}/reviews`. `customer.getFirstName()` initializes a lazy `Customer` proxy per review — a product with 500 reviews issues 501 queries. — `service/ReviewService.java:61`
  Fix: `@EntityGraph(attributePaths = "customer")` on `findByProductIdOrderByCreatedAtDesc`.

- **[n+1]** `Order.payment` is `@OneToOne(mappedBy = "order")` with no `fetch` attribute, so it defaults to **EAGER** — Hibernate issues a payment SELECT for every order loaded, including on code paths that never read it. Every other association in the codebase explicitly declares `LAZY`, which makes this look unintentional. — `entity/Order.java:75`
  Fix: this one can't be fixed by adding `fetch = LAZY` alone — a nullable `mappedBy` `@OneToOne` still needs a query to know whether the row exists. Join-fetch it in the order queries (as above), or make it `optional = false` if a payment row is always created (which `create()` does today).

- **[validation]** Unbounded collection endpoints. All four list handlers return `List<DTO>` straight off `findAll()` with no `Pageable`. Fine at seed-data scale, an OOM and a multi-second response at 500k rows — and it degrades silently as data grows. — `controller/OrderController.java:29`, `controller/ProductController.java:30`, `controller/CustomerController.java:30`, `controller/CategoryController.java:30`
  Fix: accept `Pageable` and return `Page<DTO>`; cap the page size with `spring.data.web.pageable.max-page-size`.

- **[validation]** Unbounded input sizes on request DTOs. `CreateOrderRequest.items` has `@NotEmpty` but no `@Size(max = …)` — a single request can carry 10,000 line items, each triggering a product lookup and a stock write inside one transaction. Free-text fields mapping to `TEXT` columns are also uncapped: `shippingAddress`/`notes`, `ProductRequest.description`, `CustomerRequest.address`, `CreateReviewRequest.comment`. — `dto/CreateOrderRequest.java:32`, `dto/CreateReviewRequest.java:25`
  Fix: `@Size(max = 100)` on `items`, `@Size(max = 2000)` (or similar) on the free-text fields.

- **[error-handling]** No fallback exception handler. `GlobalExceptionHandler` covers four cases; anything else — most importantly `DataIntegrityViolationException` from the unique constraints and `HttpMessageNotReadableException` from a malformed enum value — escapes the advice and returns Spring's default error body rather than the project's `ErrorResponse` contract. — `exception/GlobalExceptionHandler.java:36`
  Fix: add `@ExceptionHandler(DataIntegrityViolationException.class)` → 409, `HttpMessageNotReadableException` → 400, and a last-resort `Exception` → 500 that logs the throwable and returns a generic message.

- **[config]** Dev-only JPA settings apply to every profile. `show-sql=true` plus `format_sql=true` with no `application-prod` profile means production logs every SQL statement. — `src/main/resources/application.properties:8`
  Fix: move both to an `application-dev.properties` and add a prod profile that leaves them off.

- **[config]** `db/schema.sql` opens with `DROP DATABASE IF EXISTS ecommerce_db;` and ends with seed `INSERT`s. It isn't on the classpath so Boot won't run it, but it is the documented way to create the schema — running it against the wrong connection destroys production. — `db/schema.sql:1`
  Fix: adopt Flyway or Liquibase for versioned migrations and split the destructive bootstrap/seed portion into a clearly-named dev-only script.

## 🔵 Info

- **[concurrency]** No `@Version` on `Product`, the most contended entity in the system (stock is written by both order creation and cancellation). — `entity/Product.java:28`
  Fix: add a `@Version private Long version;` field plus the matching column, and retry on `OptimisticLockException`.

- **[concurrency]** Check-then-insert uniqueness races on customer email, product SKU, and category name/slug. Downgraded from Warning because the schema has real unique constraints (`uk_customers_email`, `uk_products_sku`, `uk_categories_name`/`_slug`) — the second concurrent insert fails at the DB. It currently surfaces as an unhandled 500 though. — `service/CustomerService.java:36`
  Fix: catch `DataIntegrityViolationException` and translate it to the same `DuplicateResourceException` → 409.

- **[transactions]** Self-invocation: `updateStatus` calls `cancel(id)` through `this`, so the proxy is bypassed and `cancel`'s own `@Transactional` never applies on that path. Harmless today — the caller is already transactional with default `REQUIRED` propagation — but it would silently break if `cancel` ever needed `REQUIRES_NEW`, and it re-fetches the order redundantly. — `service/OrderService.java:147`
  Fix: extract the shared logic into a private method taking the already-loaded `Order`.

- **[error-handling]** `BusinessRuleException` messages echo internal inventory state — `"Insufficient stock for product 'X': requested 5, available 2"` lets an anonymous client enumerate exact stock levels for the whole catalogue. — `service/OrderService.java:94`
  Fix: return a generic message and log the detail server-side.

- **[dto]** `PaymentMethod` and `OrderStatus` are imported from the `entity` package into request DTOs. Not boundary leakage — both are `public enum` declarations that serialize to plain strings — but it couples the wire contract to the persistence package. — `dto/CreateOrderRequest.java:3`
  Fix: optional — move shared enums to a neutral package.

- **[data-integrity]** `reviews.customer_id` is a foreign key with no index, unlike every other FK in the schema. — `db/schema.sql:172`
  Fix: `CREATE INDEX idx_reviews_customer ON reviews (customer_id);`

## ✅ Passed

- **transactions**: every write path in all five services carries `@Transactional`, and every read path carries `@Transactional(readOnly = true)`. `OrderService.create` — the multi-write method that matters most — is correctly transactional across the order, items, payment, and stock decrements.
- **dto**: no `@RestController` method returns a JPA entity, and no entity is bound as a `@RequestBody`. All 21 handlers return a `*Response` DTO, `List<*Response>`, or `ResponseEntity<Void>` (correct 204 on DELETE). No DTO embeds an entity field.
- **validation**: every `@RequestBody` is annotated `@Valid`, including `@Valid` cascading into the nested `List<OrderItemRequest>`. Constraints are sensible: `@Email`, `@Min(1)` on quantity, `@Min(1) @Max(5)` on rating, `@DecimalMin("0.0")` on price, `@PositiveOrZero` on stock.
- **error-handling**: `GlobalExceptionHandler` is a real `@RestControllerAdvice` returning a consistent `ErrorResponse` with correct status mapping (404 / 422 / 409 / 400-with-field-errors). No stack traces in responses; no swallowed exceptions on any write path.
- **config**: `ddl-auto=validate` is the correct production setting. Actuator is on the classpath but `management.endpoints.web.exposure.include` is unset, so it defaults to `health` only — no `/env` or `/heapdump` exposure.
- **money**: all monetary fields use `BigDecimal` in Java and `NUMERIC` in Postgres — no floating-point money anywhere. Order status transitions are guarded by an explicit `ALLOWED_TRANSITIONS` whitelist.

**Swagger caveat:** `springdoc-openapi-starter-webmvc-ui` is compile-scope with no profile gating (`pom.xml:51`), so `/swagger-ui.html` and `/v3/api-docs` will be publicly reachable in production. Folded into the auth Critical rather than listed separately.
