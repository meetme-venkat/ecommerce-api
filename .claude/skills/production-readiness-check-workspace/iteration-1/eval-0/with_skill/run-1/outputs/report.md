# Production Readiness Report

**Verdict: NOT READY — 2 critical, 5 warning, 3 info**
Fix the stock-oversell race in `OrderService.create` and get the DB password out of the config file before this goes anywhere near prod.

## 🔴 Critical
- **[concurrency]** Stock oversell: read-check-write on `stockQuantity` with no lock, no `@Version`, and no atomic DB update. Two concurrent orders for the same product both read the old stock, both pass the check, both decrement — stock goes negative and you oversell. — [`OrderService.java:93`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L93)
  Fix: make the decrement atomic in the DB (`UPDATE products SET stock_quantity = stock_quantity - :qty WHERE id = :id AND stock_quantity >= :qty`, check rows-affected and throw if 0), or add `@Version` optimistic locking with retry, or a `@Lock(PESSIMISTIC_WRITE)` finder on the hot path. The same read-modify-write shape in `cancel()` (line 167) should move to an atomic increment too.
- **[config]** Blank datasource password committed in config; username hardcoded, nothing externalized. A blank DB password means the database is protected only by network reachability. — [`application.properties:5`](src/main/resources/application.properties#L5)
  Fix: reference an env var / secrets manager (`spring.datasource.password=${DB_PASSWORD}`, `spring.datasource.username=${DB_USERNAME}`) and set a real password on the prod DB. If this DB has ever run with the blank password, treat the credential as compromised and set/rotate it — removing the line does not undo git history.

## 🟠 Warning
- **[n+1]** `findAll` maps each order through `toResponse`, touching lazy `items`, `item.getProduct()`, `customer`, and `payment` — one extra SELECT per association per order on an unbounded list endpoint. — [`OrderService.java:58`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L58)
  Fix: add a `@Query` with `join fetch` (items + product + customer) or an `@EntityGraph` finder; use `@BatchSize` on the collections. Only one collection can be join-fetched per query.
- **[n+1]** `findAll` maps each product through `toResponse`, which reads `product.getCategory().getName()` — lazy `@ManyToOne` fires one SELECT per product row. — [`ProductService.java:26`](src/main/java/com/venkat/ecommerce/api/service/ProductService.java#L26)
  Fix: `@EntityGraph(attributePaths = "category")` on the finder, or a `join fetch` query.
- **[n+1]** `findByProduct` reads `review.getCustomer()` per review in `toResponse` — one SELECT per review for a product that may have many. — [`ReviewService.java:50`](src/main/java/com/venkat/ecommerce/api/service/ReviewService.java#L50)
  Fix: `join fetch r.customer` in `findByProductIdOrderByCreatedAtDesc`, or an `@EntityGraph`.
- **[config]** `show-sql=true` / `format_sql=true` with no dev/prod profile separation — every SQL statement (and its bound values) hits prod logs: noise plus potential data leakage. — [`application.properties:8`](src/main/resources/application.properties#L8)
  Fix: set both to `false` in the default/prod config and gate them behind a `dev` profile.
- **[data-integrity]** The "stock never goes negative" invariant lives only in Java; the `products` table has no `CHECK (stock_quantity >= 0)` backstop, so if the app check is bypassed or loses the race the DB still accepts negative stock. — [`schema.sql:41`](db/schema.sql#L41)
  Fix: add `CONSTRAINT chk_products_stock CHECK (stock_quantity >= 0)` so the database refuses bad data regardless of app logic.

## 🔵 Info
- **[error-handling]** The advice maps the four known exceptions but has no catch-all `@ExceptionHandler(Exception.class)`. Any unexpected exception (including the `DataIntegrityViolationException` from a concurrent duplicate insert) falls through to Spring's default handling and breaks the consistent `ErrorResponse` contract. — [`GlobalExceptionHandler.java:16`](src/main/java/com/venkat/ecommerce/api/exception/GlobalExceptionHandler.java#L16)
  Fix: add a fallback handler returning a generic 500 body with no exception text or stack trace.
- **[concurrency]** Check-then-insert for uniqueness (`findByEmail`/`findBySku`/`findByName`/`findBySlug` then `save`) is racy, but each is backed by a DB unique constraint so the second insert fails cleanly rather than duplicating. — [`CustomerService.java:36`](src/main/java/com/venkat/ecommerce/api/service/CustomerService.java#L36)
  Fix: rely on the unique constraint and translate the resulting violation to a 409 (ties into the catch-all handler above).
- **[concurrency]** No `@Version` on any updatable entity (`Product`, `Order`, `Payment`), so concurrent updates to the same row silently lose-update. — [`Product.java:28`](src/main/java/com/venkat/ecommerce/api/entity/Product.java#L28)
  Fix: add a `@Version` field to contended entities and handle `OptimisticLockException` with a retry.

## ✅ Passed
- transactions: every write method (`create`/`update`/`delete`/`updateStatus`/`cancel`) is `@Transactional`; all read paths use `@Transactional(readOnly = true)`. No dirty-checking write left uncovered.
- dto: every controller handler returns a `*Response` DTO or `ResponseEntity<Void>` — no entity, `List<Entity>`, or `Page<Entity>` leaks, and no entity is bound as `@RequestBody`.
- validation: `@Valid` on all request bodies, nested `@Valid` on order items; DTO fields carry appropriate `@NotNull`/`@NotBlank`/`@Email`/`@Size`/`@Min`/`@Max`/`@PositiveOrZero` constraints.
- error-handling: `@RestControllerAdvice` maps business/validation exceptions to correct status codes (404, 409, 422, 400) with a structured body — no stack traces or raw messages beyond controlled business text.
- config: `spring.jpa.hibernate.ddl-auto=validate` — correct for prod, no schema-mutating or drop-on-boot setting.

---

**Want me to fix these? I'd start with the two critical items.** Note which is which before you say go:

- Mechanical / low-risk: add the `CHECK (stock_quantity >= 0)` constraint, flip `show-sql`/`format_sql` off behind a profile, add the catch-all exception handler, add `@EntityGraph`/`join fetch` for the three N+1 endpoints.
- Needs judgment / behavior change: the oversell fix (atomic decrement vs. optimistic vs. pessimistic locking — I'd recommend the conditional atomic `UPDATE`), adding `@Version` fields, and externalizing the datasource credentials. The blank password is a config + secrets change, not just a code edit — it must be **set and rotated on the actual database**, since a code change can't un-leak a credential that's already in git history.
