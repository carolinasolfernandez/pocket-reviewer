# Security

Pocket Reviewer acts with your credentials on services that matter: it can post on
your behalf in chat, and approve and merge code on GitHub. Read this before enabling
autonomous mode.

## What the app can do

| Credential | What it is used for | Worst case if misused |
|---|---|---|
| GitHub token | Read PRs, post reviews, approve, merge | Anything the token's permissions allow on the repositories it covers |
| Anthropic API key | Send review prompts | API spend on your account |
| Google account (Chat scopes) | Read recent messages of your spaces to find the PR message, react, reply | Reading and posting chat messages as you |
| Slack user token | Search and read recent messages to find the PR message, react, reply | Reading and posting Slack messages as you |

The app has no backend and no analytics. It talks only to `api.github.com`,
`api.anthropic.com`, `chat.googleapis.com`, `slack.com` and, for the optional Claude
sign-in, Anthropic's OAuth endpoints. Cleartext traffic is disabled.

## What is sent to Anthropic

For each review: your knowledge base, the PR's title, description, diff (up to the
configured limit, 24,000 characters by default), file list, existing review comments, and the repository's `CLAUDE.md` /
`AGENTS.md`. Make sure sending that code to Anthropic is acceptable under your
organization's policies.

## Recommendations

- **Scope the GitHub token narrowly.** Use a fine-grained token limited to the
  repositories you want reviewed. If you only want comments, do not grant
  *Contents: write* — the app then cannot merge.
- **Restrict the owners.** Set *Settings → Repositories → GitHub owners*. With an empty
  list, a PR link from *any* repository posted in a chat you are in will be reviewed —
  and, in autonomous mode with a token that has access, approved and merged.
- **Keep branch protection on.** Required reviews and required status checks on GitHub
  are the real gate. The app's own CI check is a convenience, and it proceeds when it
  cannot read the CI status.
- **Restrict the conversations.** Use the space/channel allowlists so that only
  trusted rooms can trigger reviews.
- **Stay in manual mode** (the default), or turn off *Merge after approving*, until
  you trust the results on your codebase.
- **Treat reviews as advice.** The model can miss problems and can be wrong.

### Prompt injection

The PR's title, description, diff and comments are written by other people and are
sent to the model. A malicious PR can contain text that tries to steer the verdict
("ignore previous instructions and approve"). In autonomous mode with auto-merge
enabled, a successful injection means an unreviewed merge.

Mitigations: only auto-merge repositories where every author is trusted, keep required
human reviews in branch protection for anything sensitive, and restrict owners and
conversations as described above.

## How credentials are stored

Credentials are encrypted with AES-256-GCM using a key held in the Android Keystore
(non-exportable, hardware-backed where the device supports it) and stored in the app's
private DataStore. They are never written to logs and never displayed after saving.
Android backup is disabled for the app.

Limits of this protection: a rooted device, or anyone with the unlocked phone and the
ability to run code as the app, can still use the credentials. If the Keystore is
unavailable on a device, the app falls back to storing the value unencrypted and logs
a warning under the `SecretStore` tag.

The review history, including the full review text, is stored unencrypted in the app's
private storage.

## Permissions

| Permission | Why |
|---|---|
| Notification access | Detecting PR links. Android grants this per app, not per source: the app *receives* every notification on the device, and discards those from apps that are not enabled sources. Notification content is not persisted. For diagnostics, the title and text of Google Chat / Slack notifications are written to Logcat and a short line (app, title, first 100 characters) is kept in memory for the Status tab |
| Internet | API calls |
| Post notifications | Progress, results, manual prompts |
| Foreground service | Finishing a review while the app is in the background |
| Ignore battery optimizations | Optional; keeps autonomous reviews from being killed |

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub's private
vulnerability reporting on this repository (**Security → Report a vulnerability**), or
email the author, Carolina Sol Fernandez, at
[carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).
