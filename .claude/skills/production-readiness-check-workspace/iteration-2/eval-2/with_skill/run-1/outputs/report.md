# Production Readiness Report

**Verdict: NOT READY - 6 critical, 12 warning, 6 info**
The API has no authentication layer at all: every endpoint - including bulk customer PII and `DELETE` on any resource - is anonymously callable, and that must be fixed before anything else on this list.

## 🔴 Critical

- **[auth]** No security layer exists anywhere. `pom.xml` has no `spring-boot-starter-security` (only actuator, data-jpa, validation, webmvc, springdoc), and there is no `SecurityFilterChain`, auth filter, or `@PreAuthorize`/`@Secured` anywhere in `src/main/java`. Every one of the 5 controllers is fully anonymous - [`pom.xml:29`](pom.xml#L29)
  Fix: add `spring-boot-starter-security` with a deny-by-default `SecurityFilterChain`, authenticate via JWT or session, and `permitAll()` only the genuinely public catalogue reads (`GET /api/v1/categories`, `GET /api/v1/products`). This is a design change - plan it, do not bolt it on.

- **[auth]** `GET /api/v1/customers` returns every customer first name, last name, email, phone and address with no authentication - a one-request bulk PII exfiltration endpoint - [`CustomerController.java:30`](src/main/java/com/venkat/ecommerce/api/controller/CustomerController.java#L30), response fields at [`CustomerResponse.java:16`](src/main/java/com/venkat/ecommerce/api/dto/CustomerResponse.java#L16)
  Fix: require authentication; restrict list access to an admin role and `/customers/{id}` to the owning principal.

- **[auth]** `GET /api/v1/orders` returns every order in the system - customer name, shipping address, line items, totals and payment method/status - to any anonymous caller - [`OrderController.java:29`](src/main/java/com/venkat/ecommerce/api/controller/OrderController.java#L29)
  Fix: scope the query to the authenticated customer; admin-only for the unscoped list.

- **[auth]** Unauthenticated destructive mutations. `DELETE /api/v1/customers/{id}`, `DELETE /api/v1/products/{id}` and `DELETE /api/v1/categories/{id}` take a sequential `Long` id anyone can enumerate, with no auth and no ownership check - [`CustomerController.java:51`](src/main/java/com/venkat/ecommerce/api/controller/CustomerController.java#L51), [`ProductController.java:51`](src/main/java/com/venkat/ecommerce/api/controller/ProductController.java#L51), [`CategoryController.java:51`](src/main/java/com/venkat/ecommerce/api/controller/CategoryController.java#L51)
  Fix: require an admin role on all write verbs for catalogue resources; add ownership checks on customer-scoped resources.

- **[auth]** Client-supplied identity (IDOR). The server trusts `customerId` straight from the request body when creating orders and reviews - any caller can place an order billed/shipped under another customer, or post a review as them - [`CreateOrderRequest.java:22`](src/main/java/com/venkat/ecommerce/api/dto/CreateOrderRequest.java#L22) consumed at [`OrderService.java:71`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L71); [`CreateReviewRequest.java:18`](src/main/java/com/venkat/ecommerce/api/dto/CreateReviewRequest.java#L18) consumed at [`ReviewService.java:31`](src/main/java/com/venkat/ecommerce/api/service/ReviewService.java#L31)
  Fix: drop `customerId` from both request DTOs and derive the customer from the `Authentication` principal.

- **[config]** Blank database password committed in the only config file - `spring.datasource.password=` with user `postgres`. The DB is protected by network reachability alone, and the credential is not externalized - [`application.properties:5`](src/main/resources/application.properties#L5)
  Fix: set a real password, move it (and the username/URL) to `${DB_PASSWORD}` / env vars or a secrets manager. Since the file is committed, treat any real value that was ever in it as leaked and rotate it.

- **[concurrency]** Unguarded read-check-write on product stock. `create()` reads `getStockQuantity()`, compares it to the requested quantity, then writes `stock - qty` with no lock and no atomic DB update. Two concurrent orders for the last unit both pass the check and both commit - stock goes negative and the shop oversells - [`OrderService.java:93`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L93) (check) and [`OrderService.java:100`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L100) (write)
  Fix: make the decrement atomic - `@Modifying @Query("update Product p set p.stockQuantity = p.stockQuantity - :qty where p.id = :id and p.stockQuantity >= :qty")` and throw `BusinessRuleException` when rows-affected is 0. Alternatively `@Lock(PESSIMISTIC_WRITE)` on the product lookup in the order path. The same non-atomic pattern restores stock on cancel at [`OrderService.java:167`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L167).

## 🟠 Warning

- **[auth]** Swagger UI / OpenAPI docs are on the classpath with no exposure gating, so `/swagger-ui.html` and `/v3/api-docs` publish the full anonymous attack surface in every profile - [`pom.xml:52`](pom.xml#L52), [`OpenApiConfig.java:14`](src/main/java/com/venkat/ecommerce/api/config/OpenApiConfig.java#L14)
  Fix: gate `springdoc.api-docs.enabled` / `swagger-ui.enabled` behind a `dev` profile, or require auth on those paths.

- **[config]** `show-sql=true` and `hibernate.format_sql=true` with no profile separation - there is a single `application.properties` and no `application-{dev,prod}.properties`. In production this floods logs and can write parameter values (including customer data) into them - [`application.properties:8`](src/main/resources/application.properties#L8)
  Fix: move both to an `application-dev.properties` and default them off.

- **[n+1]** `GET /api/v1/orders` is a compounding N+1. `orderRepository.findAll()` returns orders with a lazy `customer`, a lazy `items` collection, a lazy `product` per item, and a `payment` association; `toResponse` then touches all of them. Per order: 1 items query + 1 product query per item + 1 customer + 1 payment. 100 orders x 3 items = 600+ queries for one request - [`OrderService.java:187`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L187) (items), [`OrderService.java:191`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L191) (`getProduct().getName()`), [`OrderService.java:212`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L212) (`getCustomer().getFirstName()`)
  Fix: add a repository query `select distinct o from Order o left join fetch o.items i left join fetch i.product left join fetch o.customer` (only one collection may be join-fetched - `MultipleBagFetchException` otherwise), or `@EntityGraph`. `spring.jpa.properties.hibernate.default_batch_fetch_size=25` is a cheap app-wide backstop for the rest.

- **[n+1]** `Order.payment` is `@OneToOne(mappedBy = "order")` with no `fetch` attribute, so it defaults to **EAGER** - Hibernate fires one extra SELECT per order row on *every* order query, including code paths that never read the payment - [`Order.java:75`](src/main/java/com/venkat/ecommerce/api/entity/Order.java#L75)
  Fix: this side is nullable, so `fetch = LAZY` alone will not produce a proxy. Either join-fetch it in the list query, or mark it `optional = false` if a payment is always created with the order (it currently is, at `OrderService.java:130`) and then make it lazy.

- **[n+1]** `GET /api/v1/products` fires one extra SELECT per product for the category name. `getCategory().getId()` on line 105 is free (FK on the proxy); line 106 `getName()` is what triggers the load - [`ProductService.java:106`](src/main/java/com/venkat/ecommerce/api/service/ProductService.java#L106)
  Fix: `@EntityGraph(attributePaths = "category")` on a `findAll` override, or a `join fetch` query.

- **[n+1]** `GET /api/v1/products/{id}/reviews` fires one extra SELECT per review for the reviewer name; review counts on a popular product are unbounded - [`ReviewService.java:61`](src/main/java/com/venkat/ecommerce/api/service/ReviewService.java#L61)
  Fix: `@Query("select r from Review r join fetch r.customer where r.product.id = :productId order by r.createdAt desc")`.

- **[validation]** Unbounded list endpoints. All four collection handlers return `List<DTO>` from an unpaged `findAll()` - fine at 50 rows, a multi-second response and an OOM risk at 500,000, and it degrades silently as data grows - [`ProductController.java:30`](src/main/java/com/venkat/ecommerce/api/controller/ProductController.java#L30), [`CustomerController.java:30`](src/main/java/com/venkat/ecommerce/api/controller/CustomerController.java#L30), [`OrderController.java:29`](src/main/java/com/venkat/ecommerce/api/controller/OrderController.java#L29), [`CategoryController.java:30`](src/main/java/com/venkat/ecommerce/api/controller/CategoryController.java#L30)
  Fix: accept `Pageable` and return `Page<DTO>`; cap the page size via `spring.data.web.pageable.max-page-size`.

- **[validation]** Unbounded request collection: `CreateOrderRequest.items` has `@NotEmpty @Valid` but no `@Size(max = ...)`. A single cheap request with 10,000 line items makes the server run 10,000 product lookups inside one transaction - [`CreateOrderRequest.java:34`](src/main/java/com/venkat/ecommerce/api/dto/CreateOrderRequest.java#L34)
  Fix: add `@Size(max = 100)`.

- **[validation]** Free-text fields with no length cap, all mapped to `TEXT` columns: product/category `description`, customer `address`, order `notes`, review `comment`. Nothing stops a multi-megabyte body per field - [`ProductRequest.java:25`](src/main/java/com/venkat/ecommerce/api/dto/ProductRequest.java#L25), [`CategoryRequest.java:24`](src/main/java/com/venkat/ecommerce/api/dto/CategoryRequest.java#L24), [`CustomerRequest.java:33`](src/main/java/com/venkat/ecommerce/api/dto/CustomerRequest.java#L33), [`CreateOrderRequest.java:27`](src/main/java/com/venkat/ecommerce/api/dto/CreateOrderRequest.java#L27), [`CreateReviewRequest.java:25`](src/main/java/com/venkat/ecommerce/api/dto/CreateReviewRequest.java#L25)
  Fix: add `@Size(max = 2000)` (or domain-appropriate caps) to each.

- **[error-handling]** `GlobalExceptionHandler` maps only four exception types and has no fallback `@ExceptionHandler(Exception.class)`. Anything else - most notably `DataIntegrityViolationException` from the DB-level unique/check constraints, and `HttpMessageNotReadableException` from a bad enum value in the JSON body - bypasses the `ErrorResponse` contract and falls through to Spring default `/error`, returning a 500 with a different body shape - [`GlobalExceptionHandler.java:16`](src/main/java/com/venkat/ecommerce/api/exception/GlobalExceptionHandler.java#L16)
  Fix: add handlers for `DataIntegrityViolationException` -> 409, `HttpMessageNotReadableException` -> 400, and a catch-all `Exception` -> 500 that logs the stack trace server-side and returns a generic message. Note the project declared contract also lists `AccessDeniedException` -> 403, which is not handled (and cannot fire today, since there is no security layer).

- **[concurrency]** No optimistic locking anywhere - no `@Version` field on any entity. `Product.stockQuantity` and `Order.status` are exactly the contended fields that need it, and last-write-wins silently discards a concurrent update - [`Product.java:48`](src/main/java/com/venkat/ecommerce/api/entity/Product.java#L48), [`Order.java:53`](src/main/java/com/venkat/ecommerce/api/entity/Order.java#L53)
  Fix: add a `@Version private Long version;` to `Product` and `Order` (plus a `version` column in `schema.sql`, since `ddl-auto=validate`) and handle `OptimisticLockException` with a retry or a 409.

- **[data-integrity]** The "stock never goes negative" invariant is enforced only in Java. `products.stock_quantity` has `NOT NULL DEFAULT 0` but no `CHECK (stock_quantity >= 0)`, so the DB will happily store the negative value the race above produces - [`db/schema.sql:39`](db/schema.sql#L39)
  Fix: `ALTER TABLE products ADD CONSTRAINT chk_products_stock CHECK (stock_quantity >= 0);` - the other tables already do this correctly (`chk_order_items_quantity`, `chk_payments_amount`, `chk_reviews_rating`).

## 🔵 Info

- **[transactions]** Self-invocation: `updateStatus` calls `this.cancel(id)` directly, bypassing the proxy - the `@Transactional` on `cancel` never applies on that path. It is benign today (the caller is already transactional and `cancel` is also reachable directly from the controller), but it re-loads the order and re-runs the transition check redundantly - [`OrderService.java:147`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L147)
  Fix: extract the shared logic into a private method taking the already-loaded `Order`, so neither path depends on proxy semantics.

- **[concurrency]** Check-then-insert uniqueness races on `Customer.email`, `Product.sku`, and `Category.name`/`slug`: `findByX(...).isPresent()` then `save()`. Two concurrent creates both pass the check - but the DB unique constraints (`uk_customers_email`, `uk_products_sku`, `uk_categories_name`/`_slug`) catch the second insert, so it fails rather than corrupting data - [`CustomerService.java:36`](src/main/java/com/venkat/ecommerce/api/service/CustomerService.java#L36), [`ProductService.java:39`](src/main/java/com/venkat/ecommerce/api/service/ProductService.java#L39), [`CategoryService.java:36`](src/main/java/com/venkat/ecommerce/api/service/CategoryService.java#L36)
  Fix: no change needed for correctness - but pair with the `DataIntegrityViolationException` handler above so the loser gets a clean 409 instead of a 500.

- **[data-integrity]** Order numbers are 8 hex chars from a UUID (~4.3 billion values) against a `UNIQUE` constraint. By the birthday bound, collisions become likely in the tens of thousands of orders, and a collision throws mid-transaction with no retry - [`Order.java:81`](src/main/java/com/venkat/ecommerce/api/entity/Order.java#L81)
  Fix: use a DB sequence (`ORD-{nextval}`) or widen to 12+ chars; `orders.order_number` is `VARCHAR(20)`.

- **[dto]** Response DTOs import `OrderStatus`, `PaymentMethod` and `PaymentStatus` from the `entity` package. These are plain enums, not entities - they serialize to strings and leak nothing - but it couples the wire contract to the persistence package - [`OrderResponse.java:3`](src/main/java/com/venkat/ecommerce/api/dto/OrderResponse.java#L3), [`PaymentResponse.java:3`](src/main/java/com/venkat/ecommerce/api/dto/PaymentResponse.java#L3)
  Fix: optional - move shared enums to a neutral package (e.g. `domain` or `dto`).

- **[config]** No DB migration tool. Schema lives in a hand-run `db/schema.sql` whose first statement is `DROP DATABASE IF EXISTS ecommerce_db;` - with `ddl-auto=validate` the app will not run it, but there is no versioned, reviewable path for schema changes, and the file is a foot-gun if anyone pipes it at a live host - [`db/schema.sql:1`](db/schema.sql#L1)
  Fix: adopt Flyway or Liquibase; move the seed data into a separate, clearly-labelled dev-only script.

- **[config]** Actuator is on the classpath with no `management.endpoints.web.exposure.include` set, so only `/actuator/health` and `/actuator/info` are exposed - correct today, but nothing pins it, and with no security layer any future widening is immediately public - [`pom.xml:31`](pom.xml#L31)
  Fix: set `management.endpoints.web.exposure.include=health,info` explicitly.

## ✅ Passed

- **transactions**: every public write method in all five services carries `@Transactional`, and every read method carries `@Transactional(readOnly = true)` - no missing-transaction gaps found. The multi-write order-creation path (product decrements + order + items + payment) is correctly wrapped in one transaction.
- **dto**: no controller method returns a JPA entity, no entity is bound as a `@RequestBody`, and no response DTO embeds an entity. `ResponseEntity<Void>` on the three `DELETE` handlers is a correct 204.
- **config**: `spring.jpa.hibernate.ddl-auto=validate` is the correct production setting - schema is owned by `schema.sql`, not Hibernate. No API keys, tokens, or JWT secrets in any config file.
- **validation**: every `@RequestBody` parameter across all five controllers has `@Valid`, including `@Valid` cascading into the nested `items` list. Request DTOs carry meaningful constraints (`@NotBlank`, `@Email`, `@Min(1)`/`@Max(5)` on rating, `@PositiveOrZero` on stock, `@DecimalMin` on price).
- **error-handling**: a `@RestControllerAdvice` exists and returns a consistent `ErrorResponse` body; status codes match the project contract (404 / 422 / 409 / 400-with-field-errors). No stack traces or raw exception text are written to responses, and no exceptions are swallowed on a write path.
- **money**: all monetary fields are `BigDecimal` end to end (`Product.price`, `Order.totalAmount`, `OrderItem.unitPrice`/`subtotal`, `Payment.amount`) with matching `NUMERIC` columns - no float/double anywhere.
- **data-integrity**: FKs, unique constraints and CHECK constraints on order items, payments and reviews are all present in `schema.sql`; indexes exist on every FK used in a filter (`idx_products_category`, `idx_orders_customer`, `idx_order_items_order`/`_product`, `idx_reviews_product`).
- **concurrency**: order status transitions are validated against an explicit whitelist (`ALLOWED_TRANSITIONS`), so a cancelled order cannot be re-cancelled to double-restore stock.
