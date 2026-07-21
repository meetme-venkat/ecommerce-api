# Check Catalog

The concrete checks, grouped into seven areas. For each check: what to look for, how to find
it, how to grade it, and the fix. Work through every group. Cite a real `file:line` for each
finding — if you can't, you haven't confirmed it.

## Table of contents
0. [Auth & exposure](#0-auth--exposure) ← do this first
1. [Config & secrets](#1-config--secrets)
2. [Transaction safety](#2-transaction-safety)
3. [N+1 query risks](#3-n1-query-risks)
4. [DTO boundary leakage](#4-dto-boundary-leakage)
5. [Validation & error handling](#5-validation--error-handling)
6. [Concurrency & data integrity](#6-concurrency--data-integrity)

---

## 0. Auth & exposure

**Why it matters:** every other check in this catalog assumes an attacker has to find a bug.
Missing authentication means they don't — they just call the endpoint. This is the one area
where the defect is an *absence*, so it can't be found by grepping for a bad pattern; you
have to go looking for something that should be there and isn't. That's exactly why it gets
skipped, and why it belongs first.

**Look for:**
- **No security layer at all.** Check the build file (`pom.xml` / `build.gradle`) for
  `spring-boot-starter-security` or an equivalent, and the source for a
  `SecurityFilterChain` bean, a JWT/auth filter, or any `@PreAuthorize` / `@Secured` /
  `@RolesAllowed`. If none exist, every endpoint is anonymous. 🔴.
- **PII / customer data on open endpoints.** A `GET /customers` returning names, emails,
  addresses, or order history with no authentication is a bulk data-exfiltration endpoint.
  Rank by what the response DTO actually contains — flag the specific fields. 🔴.
- **Unauthenticated mutations.** Open `POST` / `PUT` / `DELETE`, especially `DELETE` on a
  resource anyone can enumerate by ID. Anonymous destructive access is 🔴.
- **Client-supplied identity (IDOR).** A request DTO carrying `customerId` / `userId` /
  `ownerId` that the server trusts instead of deriving from the authenticated principal.
  Any caller can act as any user by changing a number in the JSON body. 🔴 when it drives
  a write or reveals another user's data.
- **Missing ownership checks.** Even with authentication, a handler that loads a resource by
  path ID and returns it without verifying the caller owns it. 🔴/🟠 by data sensitivity.
- **Exposed operational surface.** `management.endpoints.web.exposure.include=*` (actuator
  `/env`, `/heapdump` leak config and memory), an open H2 console, Swagger UI reachable in
  production. 🟠, or 🔴 if `/env` or `/heapdump` is reachable anonymously.
- **Permissive CORS.** `allowedOrigins("*")` combined with credentials. 🟠.

**Grade:** no authentication anywhere on an API serving real user data → 🔴, and it is the
headline finding — say so in the verdict line rather than burying it in a list. Server trusts
a client-supplied user ID → 🔴. Exposed actuator/Swagger, permissive CORS, missing ownership
check on low-sensitivity data → 🟠.

**Judgment call worth making explicitly:** a deliberately public read-only catalogue endpoint
(`GET /products`) is fine unauthenticated — say so in ✅ Passed rather than flagging it. The
distinction is whether the data is *meant* to be public and whether the verb mutates state.
Note when a project looks like a learning exercise or internal demo, but still report the
finding — the user gets to decide whether it applies, and "it's just a demo" is exactly what
gets said right before something is deployed.

**Fix:** add `spring-boot-starter-security` with a `SecurityFilterChain` that denies by
default and opts specific routes into `permitAll()`; authenticate via JWT or session; derive
the acting user from the `Authentication` principal rather than the request body; add
`@PreAuthorize` ownership checks on per-user resources; restrict actuator exposure to
`health,info`. Introducing auth is a design change with real blast radius — present it as a
plan, don't silently bolt it on.

---

## 1. Config & secrets

**Why it matters:** a credential in a committed file is a credential in git history forever;
a wrong `ddl-auto` can drop tables on boot. These are the cheapest incidents to prevent and
the most expensive to clean up.

**Look for** in `application.properties` / `application.yml` (all profiles):
- **Real secrets in plaintext:** non-empty `spring.datasource.password`, API keys, JWT
  secrets, `client-secret`, tokens. A hardcoded literal is 🔴 Critical. Grep: `password`,
  `secret`, `token`, `key`, `credential`.
- **Blank/default credentials** (`password=`, `password=postgres`, `admin/admin`): 🔴 in any
  profile meant to deploy. A blank DB password means the DB is protected only by network
  reachability — treat as Critical, flag that it must move to an env var / secrets manager.
- **Not externalized:** values that should come from the environment
  (`${DB_PASSWORD}`) hardcoded instead. Externalized (`${...:default}`) is clean.
- **`spring.jpa.hibernate.ddl-auto`:** `validate` or `none` is correct for prod. `update` is
  🟠 (schema drift, silent column adds). `create` / `create-drop` in a deployable profile is
  🔴 (data loss on restart).
- **`show-sql=true` / `format_sql=true`:** fine in dev, 🔵/🟠 in prod (log noise, can leak
  data values into logs). Note it if there's no profile separation.
- **Debug surfaces:** `management.endpoints.web.exposure.include=*`, H2 console enabled,
  `server.error.include-stacktrace=always` → 🟠/🔴 depending on exposure.

**Grade:** committed real secret or `create-drop` in prod → 🔴. `ddl-auto=update`, exposed
actuator, stacktrace-to-client → 🟠. show-sql with no profile split → 🔵/🟠.

**Fix:** move secrets to environment variables or a secrets manager and reference with
`${VAR}`; set `ddl-auto=validate`; gate dev-only settings behind a `dev` profile. **A leaked
secret must be rotated** — deleting the line doesn't remove it from history.

---

## 2. Transaction safety

**Why it matters:** a public service method that performs multiple writes (or a
read-modify-write) without a transaction can half-commit when something fails partway,
leaving orphaned or inconsistent rows. Lombok/JPA make the writes invisible (dirty checking),
so the gap is easy to miss.

**Look for** in `@Service` classes:
- Public methods that call `save`, `saveAll`, `delete`, `deleteById`, `flush`, or mutate a
  managed entity (a setter on an entity loaded in the method — dirty-checking write) **without**
  `@Transactional` on the method or class.
- **Dirty-checking writes are the sneaky case:** a method that loads an entity and calls
  setters but never calls `save()` still writes on flush — and needs a transaction to define
  when that flush/commit happens. Don't wave it through just because there's no literal
  `save()`.
- Read methods should use `@Transactional(readOnly = true)` — its absence is 🔵, not a bug.
- **Self-invocation trap:** `@Transactional` on a method called from *within the same class*
  (via `this`) doesn't apply — the proxy is bypassed. If a transactional method is only ever
  reached through another method in the same class, note it (🟠).

**Grade:** multi-write or read-modify-write method with no transaction → 🔴 (partial commit
risk). Single-save method with no transaction → 🟠 (usually atomic at the JDBC level, but
still should be explicit). Missing `readOnly=true` on a query method → 🔵.

**Fix:** add `@Transactional` to the write method (or `@Transactional(readOnly = true)` for
queries). For the self-invocation trap, restructure so the call crosses a proxy boundary.

---

## 3. N+1 query risks

**Why it matters:** an endpoint that runs one query then fires another per row melts under
load — fine with 10 rows in dev, a timeout with 10,000 in prod. It hides in the innocuous
entity→DTO mapping step.

**Look for:**
- A `findAll()` / list query in a `@Service`, followed by a `toResponse()`-style mapper that
  touches a **lazy** association (`@ManyToOne(fetch = LAZY)`, `@OneToMany`, `@OneToOne`
  inverse side). Each row triggers an extra SELECT.
- Associations accessed **inside a loop** over a collection (e.g. iterating `order.getItems()`
  and reading `item.getProduct().getName()` — lazy product load per item).
- Repository JPQL (`@Query`) that returns entities with associations the caller will use, but
  has **no `JOIN FETCH`**. Derived queries (`findByX`) never fetch-join, so any lazy access
  downstream is N+1.

**Read the fetch defaults, don't assume them.** JPA's defaults differ by annotation, and the
mismatch is a reliable source of accidental eager loading that no one wrote on purpose:

| Annotation | Default fetch |
|---|---|
| `@ManyToOne` | **EAGER** |
| `@OneToOne` | **EAGER** |
| `@OneToMany` | LAZY |
| `@ManyToMany` | LAZY |

So an association with **no explicit `fetch =` attribute** is a finding worth checking, not a
default to skim past. The tell is a codebase where every other association explicitly says
`FetchType.LAZY` and one doesn't — that's almost always an oversight rather than a decision.
An eager `@OneToOne` fires a SELECT per parent row *even on code paths that never read it*.

Two related subtleties worth getting right, because they decide whether something is a
finding at all:
- **`@OneToOne(mappedBy = ...)` can't be lazily proxied when it's nullable** — Hibernate must
  query to learn whether the row exists. Making it lazy requires `optional = false` (or
  bytecode enhancement); recommending `fetch = LAZY` alone would be wrong advice.
- **`proxy.getId()` does not trigger a load** — the foreign key is already on the proxy. Only
  the *other* getters initialize it. So `.categoryId(p.getCategory().getId())` is free while
  the very next line `.categoryName(p.getCategory().getName())` is the N+1. Cite the line
  that actually causes the query.

**Grade:** N+1 on a list/collection endpoint (unbounded rows) → 🟠. N+1 on a single-aggregate
fetch (bounded, e.g. one order's items) → 🔵 or note-only — it's a fixed small number of
queries, not per-row explosion. Quantify it when you can: "100 orders × 3 items ≈ 600
queries" lands harder and is easier to verify than "possible N+1".

**Fix:** add a `JOIN FETCH` query in the repository (`select distinct o from Order o left join
fetch o.items i left join fetch i.product`), or an `@EntityGraph(attributePaths = {...})` on
the finder, or `@BatchSize` on the association to collapse N queries into a few `IN` queries.
`distinct` is required when a collection is joined, or you get duplicate parent rows. Only
one collection can be safely join-fetched per query — a second throws
`MultipleBagFetchException`, so don't recommend it.

Worth offering alongside the targeted fixes:
`spring.jpa.properties.hibernate.default_batch_fetch_size=25` turns "N individual selects"
into `WHERE id IN (?,?,…)` batches app-wide for one line. It's a backstop for the cases the
audit missed, not a replacement for fixing the hot path.

---

## 4. DTO boundary leakage

**Why it matters:** returning a JPA entity from a controller serializes whatever is on it —
today and after every future field addition. That leaks internal fields (password hashes,
audit flags), can trigger lazy-loading serialization errors, and couples your wire format to
your schema.

**Look for** in `@RestController` methods:
- Any handler whose return type is an **entity** (or `ResponseEntity<Entity>`,
  `List<Entity>`, `Page<Entity>`) instead of a DTO. Cross-reference the return type against
  the set of `@Entity` classes.
- Entities passed as `@RequestBody` (binding the request straight onto a JPA entity) — the
  inbound mirror of the same problem; allows mass-assignment of fields the client shouldn't
  set. 🟠.
- A DTO that itself embeds an entity field (leakage one level down).

**Grade:** entity returned from a public endpoint → 🔴 if the entity has sensitive/internal
fields (credentials, roles, internal flags), otherwise 🟠. Entity bound as `@RequestBody` →
🟠. `ResponseEntity<Void>` / DTO / `List<DTO>` → clean.

**Check the type before you flag it.** A DTO importing from the `entity` package looks like
leakage and usually isn't — enums (`OrderStatus`, `PaymentMethod`) often live there and
serialize to plain strings, carrying no schema or lazy state. Confirm with the declaration:
`public enum X` is clean, `@Entity class X` is the finding. If enums-in-`entity` is the only
thing you find, it's a 🔵 note about package organization coupling the wire contract to the
persistence package — not a boundary violation.

**Fix:** introduce a response DTO and map to it in the service; accept a request DTO instead
of the entity for writes.

---

## 5. Validation & error handling

**Why it matters:** untrusted input reaching the DB causes constraint-violation 500s (and
worse); unhandled exceptions leak stack traces and give clients an inconsistent error
contract.

**Look for:**
- **Missing `@Valid`:** a `@RequestBody` parameter with no `@Valid` — bean-validation
  annotations on the DTO never fire. 🟠.
- **DTOs with no constraints:** request DTOs whose fields lack `@NotNull` / `@NotBlank` /
  `@Size` / `@Min` / `@Max` / `@Email` where the domain clearly needs them (a required name
  with no `@NotBlank`, money with no `@Positive`, an email field with no `@Email`). 🟠.
- **No global exception handler:** no `@RestControllerAdvice` / `@ControllerAdvice` mapping
  exceptions to a consistent error body. Without it, business/validation exceptions surface
  as raw 500s. 🟠.
- **Stack-trace / raw-message leakage:** exception `getMessage()` or stack traces written
  into the response body; `server.error.include-stacktrace=always`. 🟠/🔴.
- **Wrong status codes:** validation failures returning 500 instead of 400, business-rule
  violations as 500 instead of 4xx (409/422). 🟠.
- **Swallowed exceptions:** `catch (Exception e) {}` or catch-and-log-and-continue on a write
  path that hides failures. 🟠.
- **Unbounded list endpoints:** a `GET` collection handler returning `List<DTO>` straight from
  `findAll()` with no `Pageable`. Fine at 50 rows, an OOM and a multi-second response at
  500,000 — and it degrades silently as data grows rather than failing an obvious test. 🟠.
- **Unbounded input sizes:** a collection field on a request DTO with no `@Size` (an order
  with 10,000 line items), or free-text with no length cap (`@Size(max = ...)` on a comment
  or description that maps to `TEXT`). Cheap request, expensive server. 🟠.

**Grade:** stack traces to clients, or a write path that swallows exceptions → 🔴/🟠 by
exposure. Missing `@Valid`, unconstrained DTOs, missing advice, wrong codes → 🟠.

**Fix:** add `@Valid` to request bodies; add the appropriate constraints to DTO fields; add
or extend `@RestControllerAdvice` to map each exception type to a status + safe body; never
put raw exception text or stack traces in responses.

---

## 6. Concurrency & data integrity

**Why it matters:** the classic e-commerce bug. Two requests read the same stock/balance,
both pass the check, both write — and you've oversold. It passes every single-threaded test
and every manual click-through, then corrupts data the first busy minute in prod.

**Look for:**
- **Read-check-write races:** load an entity, compare a field against a threshold, then write
  a decremented value — with no lock and no atomic DB update. The canonical shape:
  `if (product.getStock() < qty) throw; product.setStock(product.getStock() - qty);`. Two
  concurrent callers both read the old value. 🔴 for anything money/stock/balance.
- **No optimistic locking:** entities that are updated concurrently but have no `@Version`
  field. 🔵/🟠 depending on how contended the row is.
- **Missing DB-level guardrails behind an app-level check:** a "stock ≥ 0" invariant enforced
  only in Java, with no `CHECK` constraint or unique constraint as a backstop. 🟠.
- **Non-atomic "check then insert" for uniqueness:** `findByEmail(...).isPresent()` then
  `save()` with no unique constraint — two concurrent signups both pass the check. 🟠 (🔵 if a
  DB unique constraint exists as backstop, since the second insert then fails cleanly).
- **Missing indexes** behind frequent lookups / foreign keys used in filters → 🔵 (perf, not
  correctness).

**Grade:** unguarded read-check-write on money/stock/balance → 🔴 (real data corruption).
No `@Version` on a contended entity, app-only invariant with no DB backstop → 🟠. Missing
index, uniqueness race with a DB unique constraint present → 🔵.

**Fix:** make the update atomic in the DB (`UPDATE product SET stock = stock - :qty WHERE id
= :id AND stock >= :qty` and check rows-affected), or add optimistic locking (`@Version`) and
retry, or pessimistic lock (`@Lock(PESSIMISTIC_WRITE)`) for the hot path. Back critical
invariants with DB `CHECK`/unique constraints so the database refuses bad data even if the app
logic is bypassed.
