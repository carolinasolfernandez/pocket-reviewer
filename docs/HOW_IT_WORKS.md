# How it works

What the app does from the moment a PR link appears in a chat until the PR is
commented on or merged.

## Detection

The app registers an Android notification listener. For every notification posted on
the phone:

1. **Master switch and source.** Ignored unless "Listen for PR links" is on and the
   notification's app is an enabled source (Google Chat, Slack, or — if enabled — any
   other app).
2. **Conversation allowlist.** For Google Chat and Slack, ignored unless the
   notification's title or conversation name matches the configured spaces/channels
   (an empty list matches everything).
3. **De-duplication.** Each Android notification is processed once, even if the system
   re-posts it. The same PR sent again in a *new* message is processed again — that is
   how you ask for a re-review.
4. **Newest message only.** Chat apps bundle earlier messages into a notification;
   only the most recent message is scanned, so older links are not re-triggered.
5. **Own messages.** If the newest message is one of the app's own thread replies
   coming back as a notification, it is ignored.
6. **Link extraction and filters.** `https://github.com/<owner>/<repo>/pull/<n>` links
   are extracted, then filtered by the allowed owners and ignored repositories.

Each PR that survives is added to **History** and handed to the active mode.

## What a review consists of

Both modes run the same review:

1. Fetch from GitHub: PR metadata, the diff, the list of changed files, existing
   reviews and comments, the repository's `CLAUDE.md` / `AGENTS.md`, optionally related
   PRs, and whether the PR is mergeable.
2. Build the prompt from the [knowledge base](KNOWLEDGE_BASE.md) and the PR.
3. Ask Claude. The first line of the answer is the verdict: `APPROVE`, `COMMENT` or
   `REQUEST_CHANGES`.
4. Read the CI status of the PR's head commit.

If the app reviewed the same PR before, the previous review is included and Claude is
asked which points were addressed.

## Autonomous mode

```
notification
   │
   ├─ wait a random delay (default 2–5 min)
   ├─ react 👀 on the chat message
   ├─ review
   │
   ├─ verdict COMMENT / REQUEST_CHANGES
   │     ├─ post the review on GitHub (as a comment review)
   │     ├─ replace 👀 with 💬
   │     └─ reply in the thread ("I left some comments on the PR")
   │
   └─ verdict APPROVE          (stops here if "Approve clean PRs" is off)
         ├─ merge conflicts?  → don't approve · reply in thread · notify
         ├─ CI running?       → wait, up to "Wait for CI"
         ├─ CI failed?        → don't approve · notify (nothing is written to chat)
         ├─ approve the PR    → on failure: reply in thread · notify
         │                      (stops here if "Merge after approving" is off)
         ├─ CI still running after the wait? → leave approved, don't merge
         └─ merge             → replace 👀 with ✅
```

Details worth knowing:

- **A clean review posts nothing on GitHub** other than the approval itself, to avoid
  noise.
- **Reviews are never posted as "changes requested".** Findings are posted as a comment
  review so the app cannot block a PR.
- **CI gating.** The merge goes ahead when CI succeeded, when the commit has no checks
  at all, and also when the CI status could not be read (for example the token lacks
  permission to read checks). In that last case GitHub's branch protection is the only
  gate, so keep required checks enabled on repositories you auto-merge. A CI that
  failed, or is still running after the wait, never merges.
- **Checks you listed under "Ignored CI checks"** are left out of that calculation.
- **Several PRs in one message** are reviewed independently. The ✅ reaction is added
  once *all* of them were merged; thread replies include the PR URL so it is clear
  which one they refer to.
- **Already merged, or already in progress.** A PR this app already merged, or one
  with a review currently running, is skipped.
- **Staying alive.** A foreground service keeps the process running for the whole flow
  (delay + review + CI wait). Without the battery optimization exemption Android may
  still kill it.

## Manual mode

Nothing happens automatically except detection. You get a notification; tapping it
opens the PR in **History**, where one button runs the next step:

```
REVIEW ──► verdict APPROVE ──► APPROVE ──► MERGE ──► done
   │
   └─────► verdict with findings ──► POST ON GITHUB ──► REPLY IN CHAT ──► done
```

| Step | What it does |
|---|---|
| Review | Reacts 👀 and runs the review. Nothing is posted; you read the result in the app |
| Approve | Approves the PR on GitHub. The "Approve and merge" shortcut does this and the next step together |
| Merge | Merges with the configured method, then replaces 👀 with ✅. The current CI status is shown, but in manual mode the decision is yours |
| Post on GitHub | Posts the review text as a comment review and replaces 👀 with 💬 |
| Reply in chat | Posts the configured thread message. Skipped when that message is disabled |

Any step can be skipped. Each step runs in a foreground service, so you can leave the
app while it works. Errors are shown on the History entry.

## The Review tab

Paste any PR URL to review it on demand, independent of chat and of the owner
filters. The result is shown in the app and nothing is posted until you press a
button.

## Thread follow-ups

After the app replies in a thread, it watches that thread. When someone answers, you
get a notification with a **Re-review** action. Watched threads are listed on the
Status tab and are forgotten when the listener restarts.

## History

The last 50 PRs, newest first, with the verdict, the full review text, CI state, and
what was done (posted, approved, merged). Every action remains available from an entry,
so you can post, approve or merge later, or re-run the review.

## What is stored, and where

| Data | Location |
|---|---|
| Settings | DataStore `settings` (app-private) |
| Credentials | Same store, encrypted with an Android Keystore key |
| Downloaded / pasted rules | App-private files |
| Review history (including review text) | App-private file, last 50 entries |

Nothing is sent anywhere except to GitHub, Anthropic, Google Chat and Slack, with your
own credentials. Backups are disabled (`allowBackup=false`), so none of it leaves the
device through device backup.

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
