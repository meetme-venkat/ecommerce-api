# Production Readiness Report

**Verdict: NOT READY — 2 critical, 6 warning, 3 info**
Fix the stock oversell race in `OrderService.create` and get the database password out of the committed config (and rotated) before this ships.

## 🔴 Critical
- **[concurrency]** Oversell race: stock is read, checked against the requested quantity, then decremented with no lock, no `@Version`, and no atomic DB update — two concurrent orders both read the same stock and both pass the check, driving stock negative / overselling — [`OrderService.java:93`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L93)
  Fix: make the decrement atomic in the DB (`UPDATE products SET stock_quantity = stock_quantity - :qty WHERE id = :id AND stock_quantity >= :qty`, check rows-affected), or add `@Version` optimistic locking on `Product` and retry, or a `PESSIMISTIC_WRITE` lock on the read. The same non-atomic read-modify-write is in `cancel()` (stock restore) — [`OrderService.java:165`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L165).
- **[config]** Blank database password committed in config, alongside the default `postgres` username — the DB is protected only by network reachability, and the values are hardcoded rather than externalized — [`application.properties:5`](src/main/resources/application.properties#L5)
  Fix: move url/username/password to environment variables (`${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`) or a secrets manager. Set a real password. Note: anything ever committed here must be **rotated**, not just deleted — it stays in git history.

## 🟠 Warning
- **[n+1]** `Order` list endpoint is N+1: `findAll()` then `toResponse` walks `items`, `item.getProduct()`, `customer`, and `payment` — all LAZY — firing multiple extra SELECTs per order — [`OrderService.java:58`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L58)
  Fix: add a `JOIN FETCH` / `@EntityGraph` finder covering items+product+customer+payment (only one collection can be join-fetched per query), or `@BatchSize` on the associations.
- **[n+1]** `Product` list endpoint is N+1: `findAll()` then `toResponse` reads `product.getCategory()` (LAZY) per row — [`ProductService.java:26`](src/main/java/com/venkat/ecommerce/api/service/ProductService.java#L26)
  Fix: `@EntityGraph(attributePaths = "category")` on the finder or a `JOIN FETCH` query.
- **[n+1]** Review list endpoint is N+1: `findByProductIdOrderByCreatedAtDesc` then `toResponse` reads `review.getCustomer()` (LAZY) per review — [`ReviewService.java:50`](src/main/java/com/venkat/ecommerce/api/service/ReviewService.java#L50)
  Fix: add a `JOIN FETCH r.customer` query or `@EntityGraph` on the finder.
- **[config]** `show-sql=true` and `format_sql=true` ship in the single, deployable properties file with no profile separation — log noise and potential data exposure in prod logs — [`application.properties:8`](src/main/resources/application.properties#L8)
  Fix: gate these behind a `dev` profile (`application-dev.properties`); keep them off in the default/prod config.
- **[error-handling]** No handler for `DataIntegrityViolationException` and no catch-all `@ExceptionHandler(Exception.class)` — a DB constraint violation (e.g. the concurrent-duplicate case below, or a not-null breach) surfaces as a raw 500 instead of a clean 4xx, breaking the consistent error contract — [`GlobalExceptionHandler.java:16`](src/main/java/com/venkat/ecommerce/api/exception/GlobalExceptionHandler.java#L16)
  Fix: add a handler mapping `DataIntegrityViolationException` to 409 and a generic `Exception` fallback to 500 with a safe body (no stack trace / raw message).
- **[data-integrity]** The "stock never goes negative" invariant is enforced only in Java; the `stock_quantity` column has no DB `CHECK (stock_quantity >= 0)` backstop, so if the app check is bypassed or races, the DB accepts bad data — [`Product.java:46`](src/main/java/com/venkat/ecommerce/api/entity/Product.java#L46)
  Fix: add a `CHECK (stock_quantity >= 0)` constraint (via `@Check`/migration) as a database-level guardrail behind the app logic.

## 🔵 Info
- **[concurrency]** No optimistic-locking `@Version` on any mutable entity (`Product`, `Order`, `Payment`) — concurrent updates last-write-wins silently — [`Product.java:28`](src/main/java/com/venkat/ecommerce/api/entity/Product.java#L28)
  Fix: add a `@Version` field to contended entities (also resolves the oversell path if you go the optimistic-locking route).
- **[concurrency]** Check-then-insert for uniqueness (`findByEmail`/`findBySku`/`findByName`/`findBySlug` -> `save`) can let two concurrent requests both pass the check — but each target column has a DB `unique` constraint as backstop, so the second insert fails at the DB rather than duplicating — [`CustomerService.java:36`](src/main/java/com/venkat/ecommerce/api/service/CustomerService.java#L36)
  Fix: none required for correctness; handle the resulting `DataIntegrityViolationException` cleanly (see the warning above) so the loser gets a 409, not a 500. Also `ProductService.java:39`, `CategoryService.java:36`.
- **[data-integrity]** No explicit indexes on foreign-key / lookup columns (e.g. `reviews.product_id` behind `findByProductIdOrderByCreatedAtDesc`, `order_items.product_id`, `products.category_id`) — Postgres does not auto-index FKs — [`Review.java:33`](src/main/java/com/venkat/ecommerce/api/entity/Review.java#L33)
  Fix: add `@Index` (via `@Table(indexes = ...)`) / migration indexes on frequently filtered FK columns.

## ✅ Passed
- transactions: every write service method (`create`/`update`/`delete`/`updateStatus`/`cancel`) is `@Transactional` and every read method is `@Transactional(readOnly = true)`; the `updateStatus -> cancel` self-invocation is harmless (already inside an active transaction, propagation just joins).
- dto: all controller handlers return response DTOs or `ResponseEntity<Void>`, and all `@RequestBody` params bind request DTOs — no JPA entities cross the web boundary in either direction.
- validation: every `@RequestBody` is `@Valid` (including nested `@Valid` on order items), and request DTOs carry appropriate constraints (`@NotBlank`, `@NotNull`, `@Email`, `@Size`, `@Min`/`@Max`, `@DecimalMin`, `@PositiveOrZero`); a `@RestControllerAdvice` maps not-found->404, business-rule->422, duplicate->409, bean-validation->400.
- config: `spring.jpa.hibernate.ddl-auto=validate` — correct for a deployable profile (no schema mutation on boot).

---

**Want me to fix these? I'd start with the two critical items.** Suggested order and what each entails:

1. **Oversell race (🔴, needs judgment)** — behavioral change: I'd switch the decrement to an atomic conditional `UPDATE` with a rows-affected check (or add `@Version` + retry). This changes how the stock path executes under contention, so it warrants review and a concurrency test.
2. **Blank/hardcoded DB credentials (🔴, mechanical + operational)** — I can externalize the datasource config to `${...}` env vars mechanically, but the leaked value must be **rotated** in your secrets store — a code edit can't un-leak what's already in git history.
3. **N+1 endpoints (🟠, needs judgment)** — adding `@EntityGraph`/`JOIN FETCH` finders and rewiring the services; I'd verify query counts after.
4. **`show-sql` profile split, `DataIntegrityViolation`/fallback handler, stock `CHECK` constraint (🟠, mostly mechanical)** — safe, contained edits.
5. **`@Version`, FK indexes (🔵)** — optional hardening, low risk.

Tell me which to take on and I'll do them in severity order, re-running the affected check after each.
