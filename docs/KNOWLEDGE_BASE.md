# Knowledge base

The knowledge base is the set of rules Claude reviews every pull request against. You
manage it in the **Rules** tab.

## What goes into a review

Every review prompt is assembled from four layers:

| Layer | Where it comes from | Scope |
|---|---|---|
| 1. Knowledge base | The source you pick in the Rules tab | Every PR |
| 2. Extra instructions | The free-text box in the Rules tab | Every PR |
| 3. Repo rules | `CLAUDE.md` (or, if absent, `AGENTS.md`) at the root of the PR's repository | PRs of that repo |
| 4. PR context | Title, description, diff, existing reviews and comments, a previous review by this app | That PR |

Layers 1 and 2 are the same for every PR, which lets them be prompt-cached: after the
first review you mostly pay for the diff.

## Choosing a source

### Built-in rules (default)

A generic, language-agnostic rule set that ships with the app. It works out of the box
and is a reasonable baseline for any codebase:

| Code | Area | Examples |
|---|---|---|
| `COR` | Correctness | Logic errors, null handling, edge cases, error handling, concurrency, resource leaks |
| `SEC` | Security | Injection, secrets, authentication and authorization, input validation, SSRF, crypto |
| `DATA` | Data and persistence | Unsafe migrations, data loss, unbounded queries, missing transactions |
| `API` | Interfaces | Breaking changes, contracts out of sync, missing timeouts |
| `TEST` | Tests | Untested changes, assertions that cannot fail, flaky tests, removed tests |
| `PERF` | Performance | Accidental O(n²), I/O in hot paths, unbounded memory |
| `MAINT` | Maintainability | Duplication, unclear code, dead code, stale docs |
| `OPS` | Operability | Missing observability, undocumented config, risky rollouts |

It also tells the reviewer *how* to review — only the change, not the whole codebase;
concrete findings with a file and line; no invented findings — and how to pick the
verdict.

You can read the full text in the app (**Rules → Rules in use**) or in
[`app/src/main/assets/default_knowledge_base.md`](../app/src/main/assets/default_knowledge_base.md).
To change the default for everyone who builds your fork, edit that file.

### GitHub repository

Use markdown files you keep in a repository — for example an engineering handbook.

| Field | Meaning |
|---|---|
| Repository | `owner/repo` or a `github.com` URL |
| Branch | Branch, tag or commit. Empty = the repository's default branch |
| Folder or file | A folder (every `.md` / `.mdx` under it, recursively) or a single file of any type. Empty = every markdown file in the repo |

Press **Download rules**. The files are fetched once and stored on the device; the
reviewer does not hit GitHub for them on each review. Press **Update rules** whenever
the repository changes.

- Private repositories are read with the GitHub token from Settings, so that token
  needs read access to the knowledge base repo. Public repositories work without one.
- Limits: 60 files and 300,000 characters. If you hit them, point at a narrower folder.
  Remember the whole knowledge base is sent with every review.

### Custom text

Paste or write the rules directly in the app. Good for a short list of conventions, or
for trying something out before committing it to a repository.

### If the selected source is empty

Until you download or paste something, the built-in rules are used, and the Rules tab
says so. A review never runs without rules.

## Extra instructions

A short free-text box that is appended to whichever source is selected, with a note
that it wins on conflict. Use it for adjustments rather than rules:

```
Be strict about missing tests for bug fixes.
Don't comment on formatting; we run a formatter in CI.
Our public API lives under /api/v2 — treat any change there as potentially breaking.
```

## Repo-specific rules

If the repository of the PR has a `CLAUDE.md` at its root (or an `AGENTS.md` when there
is no `CLAUDE.md`), its first 3,000 characters are included automatically under
"repo-specific rules". This is the right place for conventions that apply to one
repository only; you do not configure anything in the app.

## Writing a good knowledge base

The reviewer follows what you write quite literally, so the quality of the reviews
tracks the quality of the rules.

- **Give rules a code.** `PY-A`, `SEC-3`. Findings cite the code, which makes reviews
  easy to scan and rules easy to discuss.
- **State the rule, then how to spot a violation.** "Database access only through
  repositories. Flag: `session.execute` called from a route handler."
- **Say why.** One sentence of rationale lets the model apply the rule to cases you
  didn't list, and tell real violations from superficial matches.
- **Describe your architecture briefly.** Which service owns what, how requests flow,
  what is shared. Many real issues are only visible with that context.
- **Say what not to flag.** Explicit exceptions prevent recurring false positives.
- **Assign severities** for the things you care most about ("a raw SQL string built
  from input is a BLOCKER").
- **Keep it short.** Everything is sent with every review. Prefer one focused page per
  topic over a full handbook.

A minimal example:

```markdown
# Architecture
- `api` is the only service exposed publicly; it authenticates requests and forwards
  them to `core` with an `X-User-Id` header. `core` trusts that header.

# Rules
## API-A — Authentication happens at the edge
Every route in `api` must use the `require_user` dependency.
Flag: a new route without it. Severity: BLOCKER.
Not a violation: `/healthz` and `/metrics`.

## DB-A — Queries live in repositories
Flag: SQL or ORM queries outside `core/repositories/`.
Why: we rely on repositories to apply tenant scoping.
```

## Severities and verdicts

These are fixed by the app, not by the knowledge base:

| Severity | Meaning |
|---|---|
| `BLOCKER` | Security hole, data loss, production crash or outage |
| `ERROR` | A clear bug or a clear violation of a rule |
| `WARNING` | A likely problem or meaningful technical debt |
| `INFO` | An optional suggestion |

| Verdict | What the app does with it |
|---|---|
| `APPROVE` | Nothing is posted. In autonomous mode the PR may be approved and merged |
| `COMMENT` | The review is posted on GitHub; the chat message gets the "comments" reaction and a thread reply |
| `REQUEST_CHANGES` | Same as `COMMENT`. The review is posted as a comment — the app never blocks a PR with a "changes requested" review |

Your knowledge base can influence *when* each verdict is chosen (the built-in rules
have a section for it), but the three words and the response format must stay as they
are: the app parses the first line of the response.

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
