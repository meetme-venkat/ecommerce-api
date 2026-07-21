---
name: production-readiness-check
description: >-
  Audit a Spring Boot / JPA REST API for production readiness across six areas —
  auth & exposure, config & secrets, transaction safety, N+1 query risks, DTO boundary
  leakage, input validation & error handling, and concurrency / data-integrity — then
  produce one severity-graded go/no-go report and offer to fix what it finds. Use this
  whenever the user asks whether the app, service, or API is "production ready", wants a
  pre-deploy, pre-release, pre-launch, or "ship it?" / go-no-go review, a hardening /
  robustness / health audit, or mentions checking for unauthenticated endpoints, exposed
  customer data, hardcoded secrets, race conditions, oversell, missing @Transactional,
  N+1 queries, or entity leakage before deploying — even if they never say the exact
  words "production readiness".
---

# Production Readiness Check

## What this skill does

It runs a battery of static checks over a Spring Boot + Spring Data JPA REST API, grades
each finding by how badly it would hurt in production, and emits **one** report with a
go/no-go verdict at the top. Then it offers to fix the findings. The point is to catch the
handful of issues that actually cause incidents — leaked credentials, corrupted data from
races, silent transaction gaps, oversharing internals — not to produce a wall of nitpicks.

The checks are grouped into seven areas. Each is defined precisely, with the code patterns
to look for, in `references/check-catalog.md`. Read that file before auditing — it is the
substance of the skill; this file is just the workflow and the output contract.

**Start with auth.** An API with no authentication is the finding that dwarfs the others —
every other issue assumes an attacker has to work for it. It's also the easiest to overlook,
because nothing in the code is *wrong*; the security is simply absent, and absence doesn't
show up in a grep for bad patterns. Establish whether a security layer exists before you
audit anything else, so the rest of the report is framed correctly.

## Workflow

1. **Map the codebase.** Find the layers you'll be auditing so you know what exists:
   - whether a security layer exists at all — a `spring-boot-starter-security` dependency,
     any `SecurityFilterChain` / `WebSecurityConfigurerAdapter`, `@PreAuthorize`, JWT filter
   - `@Service`, `@Repository`, `@RestController` classes
   - JPA entities (`@Entity`) and their relationships / fetch types
   - `application.properties` / `application.yml` (and any profile variants)
   - the global exception handler (`@RestControllerAdvice`), if any
   - request DTOs and their bean-validation annotations

   A fast way in: check the build file for a security starter, then grep for `@Service`,
   `@RestController`, `@Entity`, `@ManyToOne`, `ddl-auto`, `password`. Prefer the dedicated
   search tools over shell `grep`.

   Absence is a finding here. If the security grep returns nothing, that's not "nothing to
   report" — it means every endpoint you're about to read is public.

2. **Run every check group** in `references/check-catalog.md`. Don't skip a group because
   the codebase "looks fine" — the whole value is systematic coverage. If a group has no
   findings, it becomes a ✅ Passed line, which is itself useful signal.

3. **Grade each finding** with the severity rubric below. Be honest about severity — a
   report that marks everything Critical is as useless as one that marks nothing.

4. **Emit the report** using the exact template below. Lead with the verdict so a reader
   gets the answer in one glance.

5. **Offer to fix.** After the report, ask before changing anything (see "Offering fixes").

## Severity rubric

Grade by blast radius, not by how easy it is to spot. Ask: *if this shipped, what happens?*

- **🔴 Critical — blocks release.** Causes data loss/corruption, leaks credentials or
  customer data, or silently drops writes. Examples: no authentication on endpoints that
  read or mutate customer data; a real secret or password committed to config; a
  check-then-write race that lets stock/balance go negative; a multi-write service method
  with no transaction so a mid-way failure half-commits; an entity serialized to clients
  that exposes password hashes or internal flags.
- **🟠 Warning — fix before it bites.** Degrades performance or correctness under load, or
  weakens the error contract, but won't corrupt data on day one. Examples: N+1 queries on a
  list endpoint; a write path missing `@Transactional` where a single save still atomic-ish;
  missing `@Valid` / bean validation so bad input reaches the DB; `ddl-auto` set to
  `update`/`create` in a deployable profile; stack traces or exception messages returned to
  clients.
- **🔵 Info — hardening, not blocking.** Best-practice gaps with low immediate risk. Examples:
  no optimistic-locking `@Version`; no DB migration tool (Flyway/Liquibase); no actuator
  health endpoint; missing DB index behind a frequent lookup.

When unsure between two levels, state the specific failure scenario and let that decide —
"could this corrupt data or leak secrets?" → Critical; "only hurts under load / bad input?"
→ Warning.

## Calibration: don't invent findings

A report's credibility is spent the first time it flags something correct. Once a reader
finds one bogus item, they discount the rest — including the real Critical. So the bar for
listing something is that you can name the failure it causes, not that it merely looks
unusual.

Patterns that are *correct* and should land in ✅ Passed, not in a severity bucket:

- **`ddl-auto=validate` or `none`** — this is the right production setting. Only `update`,
  `create`, or `create-drop` are findings.
- **Enums that live in the entity package** but are used in DTOs — enums aren't entities;
  they serialize to plain strings and leak nothing. Verify with the declaration (`public
  enum` vs `@Entity`) before calling it boundary leakage.
- **`ResponseEntity<Void>` on DELETE** — that's a correct 204 No Content, not a missing DTO.
- **A lazy association read inside an already-open transaction** on a single-aggregate
  fetch — bounded work, not an N+1 explosion.
- **`getId()` on a lazy proxy** — served from the foreign key without a query. It's the
  *other* getters that trigger a load.

If a check group genuinely has nothing wrong, say so in ✅ Passed. A short honest report
beats a padded one.

## Report structure

Use this exact template. Keep each finding to a claim, a `file:line`, and a one-line fix —
readers scan this, they don't read prose.

```markdown
# Production Readiness Report

**Verdict: <READY | READY WITH WARNINGS | NOT READY> — <N critical, M warning, K info>**
<one sentence: the single most important thing to do before shipping>

## 🔴 Critical
- **[<area>]** <what's wrong> — [`File.java:LINE`](path#LLINE)
  Fix: <the specific change>

## 🟠 Warning
- **[<area>]** <what's wrong> — [`File.java:LINE`](path#LLINE)
  Fix: <the specific change>

## 🔵 Info
- **[<area>]** <what's wrong> — [`File.java:LINE`](path#LLINE)
  Fix: <the specific change>

## ✅ Passed
- <area>: <what you verified is clean, one line each>
```

Rules that keep the report trustworthy:
- **Verdict logic:** any 🔴 → `NOT READY`. No 🔴 but ≥1 🟠 → `READY WITH WARNINGS`. Only 🔵
  or nothing → `READY`.
- **Every finding needs a real `file:line`.** If you can't point at a line, you haven't
  confirmed it — verify or drop it. No speculative findings.
- **Show the Passed section.** It proves coverage and stops the reader wondering whether a
  category was checked. Omit a severity heading entirely if it has no findings (don't print
  an empty "🔴 Critical").
- `<area>` is one of: `auth`, `config`, `transactions`, `n+1`, `dto`, `validation`,
  `error-handling`, `concurrency`, `data-integrity`.

## Offering fixes

After presenting the report, offer to fix — but let the user steer, and never touch code in
the same breath as reporting. Something like: *"Want me to fix these? I'd start with the
critical ones."* Then:

- Fix in severity order (🔴 first) unless the user picks specific items.
- Some fixes are mechanical and safe (add `@Transactional`, add `@Valid`, add a missing
  validation annotation). Others change behavior or need judgment (introducing pessimistic
  locking, restructuring a query with `JOIN FETCH`, externalizing a secret and rotating it).
  Call out which is which so the user isn't surprised.
- A leaked secret can't be un-leaked by a code edit — flag that it must be **rotated**, not
  just removed from the file, because it's in git history.
- After fixing, re-run the affected check to confirm it's actually resolved.

## Notes on scope

This is a static audit — it reads code and config, it doesn't run the app or hit a live DB.
It's tuned for Spring Boot + Spring Data JPA conventions (Lombok entities/DTOs, constructor
injection, `@RestControllerAdvice` error handling, `BigDecimal` money). If the project uses
a different stack, apply the same *intent* (secrets, atomicity, over-fetching, boundary
leakage, input trust, races) rather than looking for the exact annotations.
