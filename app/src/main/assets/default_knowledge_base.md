# Default review knowledge base

These are the rules the reviewer applies when no custom knowledge base is configured.
They are deliberately language- and framework-agnostic. Each rule has a code so a
finding can cite it (`[SEC-2]`, `[COR-1]`, …).

Repo-specific conventions (the target repo's `CLAUDE.md` / `AGENTS.md`, when present)
take precedence over anything here.

## How to review

- Review the change, not the codebase. Only raise issues that this diff introduces,
  worsens, or leaves dangerously incomplete. Pre-existing problems in untouched code
  are out of scope unless the change makes them reachable.
- Read the PR description first and judge the change against its stated intent.
- Every finding must be concrete: point at a file and line, say what goes wrong and
  under which input or condition, and suggest a fix when one is obvious.
- Prefer a few findings that matter over an exhaustive list of nitpicks. If you are
  not reasonably confident that something is a real problem, leave it out or mark it
  as a question.
- Do not ask for changes that are purely a matter of taste when the code is
  consistent with the rest of the file.
- If the diff is truncated or context is missing, say so rather than guessing.

## COR — Correctness

- **COR-1 Logic errors.** Off-by-one, inverted conditions, wrong operator, wrong
  variable, unreachable branches, a loop that never runs or never ends.
- **COR-2 Null / empty / missing.** A value that can be null, undefined, empty or
  absent is dereferenced, indexed or unwrapped without a check.
- **COR-3 Edge cases.** Empty collections, zero, negative numbers, very large input,
  unicode, time zones, daylight saving, leap days, concurrent callers.
- **COR-4 Error handling.** Errors that are swallowed, logged and ignored, turned into
  a success, or caught so broadly that unrelated failures are hidden. Cleanup that
  does not run on the error path.
- **COR-5 Concurrency.** Shared mutable state without synchronization, check-then-act
  races, blocking calls on an event loop or UI thread, missing `await`, work that
  outlives its owner.
- **COR-6 Resource handling.** Files, sockets, connections, locks, cursors,
  subscriptions or timers that are opened and not reliably closed.
- **COR-7 Behaviour change.** A change to the behaviour of an existing public
  function, endpoint or default that callers were relying on, without those callers
  being updated.

## SEC — Security

- **SEC-1 Injection.** SQL, shell, template, path or HTML built by concatenating
  untrusted input. Use parameterized queries, argument arrays, escaping, allowlists.
- **SEC-2 Secrets.** Credentials, tokens, keys or connection strings committed to the
  repo, written to logs, returned in responses or put in URLs.
- **SEC-3 Authentication and authorization.** A new route, handler, job or query that
  is reachable without authentication, or that does not check the caller is allowed
  to act on *this specific* resource (tenant, owner, role).
- **SEC-4 Input validation.** Untrusted input used without validating type, size,
  range or format; unbounded uploads, page sizes or recursion.
- **SEC-5 Sensitive data.** Personal or confidential data logged, cached, sent to a
  third party or exposed beyond what the feature needs.
- **SEC-6 Server-side requests and files.** Fetching user-supplied URLs or opening
  user-supplied paths without an allowlist (SSRF, path traversal).
- **SEC-7 Cryptography.** Home-made crypto, weak or deprecated algorithms, hardcoded
  IVs or salts, non-constant-time comparison of secrets, predictable randomness used
  for security.
- **SEC-8 Dependencies.** A new dependency that is unmaintained, unpinned, pulled from
  an unusual source, or far heavier than the problem requires.
- **SEC-9 Configuration.** Debug modes, wildcard CORS, disabled TLS verification or
  permissive defaults that would reach production.

Treat a confirmed SEC-1, SEC-2 or SEC-3 issue as a BLOCKER.

## DATA — Data and persistence

- **DATA-1 Migrations.** A schema change that is not backwards compatible with the
  code currently deployed, locks a large table, or has no safe rollback.
- **DATA-2 Data loss.** Deletes, overwrites or truncations that are unconditional,
  irreversible or run against more rows than intended.
- **DATA-3 Queries.** Queries without a limit, missing an index on a new filter,
  issued inside a loop (N+1), or loading whole tables into memory.
- **DATA-4 Transactions.** Multi-step writes that must succeed or fail together but
  are not in a transaction; external calls made while holding one.
- **DATA-5 Consistency.** Two sources of truth for the same fact; a cache that is
  never invalidated.

## API — Interfaces and compatibility

- **API-1 Breaking changes.** Removed or renamed fields, changed types, changed
  status codes or error shapes on an interface other code depends on, without a
  version bump or migration path.
- **API-2 Contracts.** Request/response shapes, events or config that changed in one
  place but not in the consumers, clients, docs or schemas that mirror them.
- **API-3 Failure behaviour.** Outbound calls without a timeout; retries on
  non-idempotent operations; no handling for the dependency being slow or down.

## TEST — Tests

- **TEST-1 Coverage of the change.** New behaviour or a bug fix with no test that
  would fail without it.
- **TEST-2 Meaningful assertions.** Tests that cannot fail: no assertions, assertions
  on mocks only, or asserting the implementation rather than the behaviour.
- **TEST-3 Reliability.** Tests that depend on real time, random values, test order,
  the network or shared state.
- **TEST-4 Removed protection.** Tests deleted, skipped or weakened to make the
  change pass, without a stated reason.

## PERF — Performance

- **PERF-1 Complexity.** Accidentally quadratic work or repeated expensive calls on
  a path that handles real-world sizes.
- **PERF-2 I/O in hot paths.** Network, disk or database access inside tight loops,
  render functions or request middleware.
- **PERF-3 Memory.** Unbounded caches, collections that only grow, loading large
  payloads fully into memory when streaming is possible.

Only raise PERF findings when the cost is plausible at the sizes this code will see.

## MAINT — Maintainability

- **MAINT-1 Duplication.** Logic copied from elsewhere in the repo when an existing
  helper could be reused or extended. Cap at the most important occurrences.
- **MAINT-2 Clarity.** Names that mislead, functions doing several unrelated things,
  deeply nested control flow, magic numbers without explanation.
- **MAINT-3 Dead code.** Unused code, commented-out blocks, leftover debug output,
  or TODOs describing work this PR was supposed to do.
- **MAINT-4 Consistency.** Code that ignores an established pattern in the same
  module (error handling, logging, layering) without a reason.
- **MAINT-5 Documentation.** Public behaviour, configuration or setup steps that
  changed while the README, comments or API docs still describe the old behaviour.

## OPS — Operability

- **OPS-1 Observability.** New failure paths with no log, metric or error surfaced
  to whoever would have to debug them.
- **OPS-2 Configuration.** New required environment variables, flags or secrets that
  are not documented or have no safe default.
- **OPS-3 Rollout.** Changes that must be deployed in a specific order, or cannot be
  rolled back, without that being called out.

## Choosing the verdict

- **REQUEST_CHANGES** — at least one BLOCKER or ERROR: the change is unsafe or wrong
  as written.
- **COMMENT** — only WARNING / INFO findings, or open questions the author should
  answer; the change could merge once they are considered.
- **APPROVE** — no findings worth the author's time. Minor style preferences alone
  are not a reason to withhold approval.
