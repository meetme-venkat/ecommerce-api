# Production Readiness Report

**Verdict: NOT READY — 2 critical, 6 warning, 3 info**
Fix the stock oversell race in `OrderService.create` and set a real, externalized DB password before this ships — both are exactly the "blow up on us" cases you called out.

## 🔴 Critical
- **[concurrency]** Stock is decremented with an unguarded read-check-write: the method reads `stockQuantity`, compares it to the requested quantity, then writes `current - qty` with no lock, no `@Version`, and no atomic DB update. Two concurrent orders both read the same stock and both pass the check → oversell / negative stock. — [`OrderService.java:93`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L93)
  Fix: make the decrement atomic in the DB — `UPDATE products SET stock_quantity = stock_quantity - :qty WHERE id = :id AND stock_quantity >= :qty` and reject the item if 0 rows were updated — or add `@Version` to `Product` and retry on `OptimisticLockException`, or `@Lock(PESSIMISTIC_WRITE)` on the product load. (Behavior change — needs judgment; not a one-line mechanical fix.)
- **[config]** DB password is blank *and* hardcoded — `spring.datasource.password=` with `username=postgres`, none externalized. A blank production DB password means the database is protected only by network reachability. — [`application.properties:5`](src/main/resources/application.properties#L5)
  Fix: set a strong password and inject it from the environment: `spring.datasource.password=${DB_PASSWORD}` (same for URL/username). Since it is blank, there is nothing to rotate — but do not commit the real value; keep it in an env var / secrets manager.

## 🟠 Warning
- **[n+1]** `findAll()` loads every order, then `toResponse` walks four lazy associations per row — `getItems()`, `getCustomer()`, each item's `getProduct()`, and `getPayment()` (`@OneToOne` inverse). On the orders list endpoint this is a query storm that melts under load. — [`OrderService.java:59`](src/main/java/com/venkat/ecommerce/api/service/OrderService.java#L59)
  Fix: add a `@EntityGraph(attributePaths = {"customer","items","items.product","payment"})` on the finder, or a `JOIN FETCH` query (one collection fetch-joined per query), or `@BatchSize` on the associations.
- **[n+1]** Product list maps each product through `toResponse`, which reads the lazy `category` (`getCategory().getId()/getName()`) → one extra SELECT per product. — [`ProductService.java:105`](src/main/java/com/venkat/ecommerce/api/service/ProductService.java#L105)
  Fix: `@EntityGraph(attributePaths = "category")` on `findAll`, or a `JOIN FETCH` query.
- **[n+1]** Review list maps each review through `toResponse`, reading the lazy `customer` (and `product`) per review → one extra SELECT per review on the per-product reviews endpoint. — [`ReviewService.java:56`](src/main/java/com/venkat/ecommerce/api/service/ReviewService.java#L56)
  Fix: fetch-join `customer`/`product` in `findByProductIdOrderByCreatedAtDesc`, or add an `@EntityGraph`.
- **[error-handling]** The advice maps the three custom exceptions but has no fallback `@ExceptionHandler(Exception.class)`. Anything unexpected — including `DataIntegrityViolationException` from a DB unique constraint — bypasses the `ErrorResponse` contract and surfaces as Spring's default raw 500 (a unique-key clash returns 500 instead of 409). — [`GlobalExceptionHandler.java:16`](src/main/java/com/venkat/ecommerce/api/exception/GlobalExceptionHandler.java#L16)
  Fix: add a catch-all `@ExceptionHandler(Exception.class)` returning a generic 500 `ErrorResponse` (no exception text), and a handler for `DataIntegrityViolationException` → 409.
- **[data-integrity]** The "stock never goes negative" invariant lives only in Java (`OrderService`); there is no DB `CHECK (stock_quantity >= 0)` backstop, so any path that bypasses the service (or the race above) can persist negative stock. — [`Product.java:46`](src/main/java/com/venkat/ecommerce/api/entity/Product.java#L46)
  Fix: add a `CHECK (stock_quantity >= 0)` constraint so the database refuses bad data even if app logic is bypassed.
- **[config]** `show-sql=true` and `format_sql=true` are on in the only (deployable) properties file — there is no profile separation, so they run in production: heavy log noise and SQL echoed on every request. — [`application.properties:8`](src/main/resources/application.properties#L8)
  Fix: move these to an `application-dev.properties` gated behind the `dev` profile; leave them off in prod.

## 🔵 Info
- **[concurrency]** No `@Version` optimistic-locking field on any entity. Beyond the critical stock path, concurrent order-status updates (`updateStatus`/`cancel`) can silently overwrite each other's last-write. — [`Order.java:37`](src/main/java/com/venkat/ecommerce/api/entity/Order.java#L37)
  Fix: add a `@Version` field to `Order` (and `Product`, which also backs the critical fix).
- **[concurrency]** Uniqueness is enforced with check-then-insert (`findByEmail/findBySku/findByName/findBySlug` then `save`), which two concurrent creators can both pass. This is low-risk only because each column has a DB `unique=true` backstop, so the second insert fails cleanly — but today that failure returns an ugly 500 (see the error-handling warning). — [`CustomerService.java:36`](src/main/java/com/venkat/ecommerce/api/service/CustomerService.java#L36)
  Fix: keep the DB unique constraints (already present) and map `DataIntegrityViolationException` → 409 so the race resolves to a clean conflict response.
- **[config]** No DB migration tool (Flyway/Liquibase); schema is governed solely by `ddl-auto=validate`. That is safe (it won't mutate schema), but there is no versioned, repeatable way to evolve the schema in production. — [`application.properties:7`](src/main/resources/application.properties#L7)
  Fix: add Flyway or Liquibase and manage schema changes as versioned migrations.

## ✅ Passed
- transactions: every write service method (`create`/`update`/`delete`/`updateStatus`/`cancel`) is `@Transactional` and every read is `@Transactional(readOnly = true)`; the `updateStatus → cancel` self-call is not a proxy trap since both already run in one transaction and `cancel` is also reachable directly via the controller.
- dto: every `@RestController` handler returns a `*Response` DTO (or `ResponseEntity<Void>`) and every `@RequestBody` binds a `*Request` DTO — no JPA entity is ever on the wire, and no entity carries credential/secret fields.
- validation: every `@RequestBody` is `@Valid` (nested `@Valid` on order items too); request DTOs carry appropriate constraints (`@NotBlank`, `@Email`, `@Min`/`@Max`, `@PositiveOrZero`, `@DecimalMin`, `@Size`); custom exceptions map to 400/404/409/422 with a consistent `ErrorResponse` body and no stack traces.
- config (schema safety): `ddl-auto=validate` — no `create`/`create-drop`/`update`, so boot will not drop or silently alter tables.
- config (actuator): actuator is on the classpath but no `management.endpoints.web.exposure.include=*` override is set, so only the default health endpoint is web-exposed — not wide open.

---

**Want me to fix these? I'd start with the two criticals.** A few notes on what's mechanical vs. what needs judgment:

- **Mechanical / safe:** add the fallback + `DataIntegrityViolationException` handlers, add a `CHECK (stock_quantity >= 0)` constraint, add `@EntityGraph` fetch plans for the three N+1 endpoints, and move `show-sql`/`format_sql` behind a `dev` profile.
- **Needs judgment (behavior change):** the stock oversell fix — atomic DB decrement vs. `@Version` + retry vs. pessimistic lock — changes the write path and how failures are surfaced, so I'd want to confirm the approach with you first. Adding `@Version` also touches every concurrent update path.
- **Config, not a code edit:** the DB password must be set for real and injected from the environment; removing the line from the file is not enough on its own.

Tell me which to take first — I can do the two criticals, or knock out all the mechanical warnings in one pass.
