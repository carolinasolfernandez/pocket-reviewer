# Troubleshooting

Start with the **Status** tab: the checklist shows what is not configured, **Test**
checks each connection, and the log at the bottom shows the most recent notifications
the app saw and what it found in them.

For more detail, connect the phone and filter Logcat:

```bash
adb logcat -s PRListener ReviewManager GoogleChatClient SlackClient SecretStore
```

## Nothing happens when a PR link is posted

| Check | How |
|---|---|
| Is the app listening? | Status → Listening must be "On" |
| Does it have notification access? | Status checklist. Some phones revoke it after an app update — toggle it off and on |
| Did the phone get a notification at all? | You are not notified of your own messages, of muted conversations, or of messages you already read on another device |
| Is the source enabled? | Settings → Sources |
| Is the conversation in the allowlist? | Settings → Sources. Try emptying the list to confirm |
| Is the owner allowed, the repo not ignored? | Settings → Repositories |
| Is it a link the app understands? | Only `https://github.com/<owner>/<repo>/pull/<n>` |
| Was this PR already merged by the app? | Then it is skipped on purpose |

The Status log shows a line for every Google Chat / Slack notification and how many
PRs were found in it. If no line appears, Android is not delivering the notification
to the listener.

In autonomous mode remember the delay: by default the review starts 2–5 minutes after
the notification.

## The review starts but never finishes (autonomous mode)

Android killed the process. Turn off battery optimization for the app (Status →
Service). On Samsung also check **Settings → Battery → Background usage limits** and
remove the app from "Sleeping apps"; other manufacturers have similar lists.

## GitHub errors

| Message | Meaning |
|---|---|
| `GitHub token is not configured` | Save a token in Settings → Accounts |
| `401` | The token is invalid or expired |
| `404` on a PR you can open in the browser | The token has no access to that repository (fine-grained tokens are per-repository) |
| Cannot approve — `422` | GitHub does not allow approving your own pull request |
| Cannot approve — `403` | Token lacks *Pull requests: Read and write* |
| Cannot merge — `403` | Token lacks *Contents: Read and write* |
| Cannot merge — `405` | Not allowed by the repository: the merge method is disabled, required reviews or checks are missing, or the branch is protected |
| Merge conflicts | The branch must be updated by its author |
| CI shows as unknown | The token cannot read checks (*Checks: Read*) |
| Every call fails on GitHub Enterprise Server | Check **Settings → Repositories → GitHub host**, that the phone can reach the server, and its TLS certificate — see [CONFIGURATION.md](CONFIGURATION.md#github-enterprise-server) |
| PR links are not detected on GitHub Enterprise Server | Links are only recognized on the configured GitHub host |

## Claude errors

| Message | Meaning |
|---|---|
| `Claude is not configured` | Save an API key in Settings → Accounts |
| `401` | Invalid API key |
| `404` / `not_found_error` mentioning the model | The model id in Settings → Review behaviour does not exist or is not available to your account |
| `429` | Rate limited; the app retries a few times |
| `400` about prompt length | The knowledge base or the **Max diff** setting is too large for the model — narrow the folder, shorten the text or lower the limit |
| `400` about `max_tokens` | **Max review length** is higher than the chosen model allows |
| The review is cut off mid-sentence | Raise **Max review length** in Settings → Review behaviour |
| The Claude session expired | *Sign in with Claude* only: sign in again, or switch to an API key |

The Status tab's **Test** sends a one-token request to the configured model and shows
the error it gets back.

## Google Chat: no reaction or reply

See the table in [GOOGLE_CHAT.md](GOOGLE_CHAT.md#troubleshooting). In short: sign-in
failing with `statusCode=10` means the OAuth client does not match the installed APK's
package name and signing certificate.

If sign-in works but the message is not found, the link may be older than the 25 most
recent messages of its space by the time the review ran.

## Slack: no reaction or reply

| Symptom | Cause |
|---|---|
| Test says `invalid_auth` / `token_revoked` | Wrong or revoked token; reinstall the Slack app and copy the User OAuth Token again |
| Test says `not_allowed_token_type` | You pasted a bot token (`xoxb-`). A user token (`xoxp-`) is required |
| `missing_scope` in Logcat | Add the scope it names under User Token Scopes and reinstall the app to the workspace |
| Review happens, no reaction | The message was not found. Check `search:read` is granted, and raise the autonomous delay — search is not instant |
| Reply works, reaction doesn't | The emoji setting is a literal emoji the app has no Slack name for; use a shortcode (`eyes`) |
| Reaction on the wrong message | The same URL was posted more than once; search returns the newest |

## The review itself is poor

- Too many nitpicks, or missing what matters → improve the rules. See "Writing a good
  knowledge base" in [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md).
- It ignores your conventions → check which rules are actually in use (Rules tab →
  Rules in use). If the selected source is empty, the built-in rules are used.
- Reviews of large PRs miss things → the diff is truncated at 24,000 characters by
  default. Raise **Max diff** in Settings → Review behaviour.
- It reviews in the wrong language → Settings → Review behaviour → Review language.

## Credentials disappeared

Credentials are encrypted with a key that never leaves the device. After restoring the
phone from a backup, or on a device whose Keystore was reset, they cannot be decrypted
and must be entered again.

## Getting help

Open an issue with: the Android version and phone model, the source (Google Chat or
Slack), the mode, and the relevant Logcat lines. **Remove tokens, PR contents and
message text first.**

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
