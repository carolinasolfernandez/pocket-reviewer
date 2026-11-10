# Configuration reference

Everything is configured in the app; there are no config files or build-time settings.
Settings are stored on the device in Jetpack DataStore.

- [General](#general)
- [Accounts](#accounts)
- [Sources](#sources)
- [Repositories](#repositories)
- [Review behaviour](#review-behaviour)
- [Messages and reactions](#messages-and-reactions)
- [Review rules](#review-rules)
- [Storage keys](#storage-keys)

## General

The two switches at the top of **Settings**. They are saved as soon as you flip them.

| Setting | Default | Effect |
|---|---|---|
| Listen for PR links | On | Master switch. Off = notifications are ignored entirely; only reviews you start from the Review tab run |
| Autonomous / Manual mode | Manual | **Autonomous**: the whole flow runs on its own. **Manual**: detected PRs are added to History and you run each step. See [HOW_IT_WORKS.md](HOW_IT_WORKS.md) |

## Accounts

Credentials are encrypted with a key in the Android Keystore and are never shown again
after saving. Typing a new value replaces the stored one; **Remove** deletes it.

| Credential | Required | Notes |
|---|---|---|
| GitHub personal access token | Yes | Permissions in [GETTING_STARTED.md](GETTING_STARTED.md#github-token) |
| Anthropic API key | Yes (or Claude sign-in) | Billed to your API account |
| Google account | No | Reactions and replies in Google Chat — [GOOGLE_CHAT.md](GOOGLE_CHAT.md) |
| Slack user token | No | Reactions and replies in Slack — [SLACK.md](SLACK.md) |

### Claude credentials

Two ways to authenticate, detected automatically:

1. **Anthropic API key** (`sk-ant-api…`) — recommended.
2. **Sign in with Claude** (experimental) — an OAuth login that uses a Claude
   subscription instead of API billing. You sign in in the browser and paste the
   authorization code back into the app.

   This reuses the public OAuth client of Anthropic's own tooling. It is **not an
   officially supported way for third-party apps to authenticate**, may be against
   Anthropic's terms for your use, and its endpoints have changed before and can stop
   working without notice. It is included because it existed in the project this app
   grew out of; prefer an API key, and do not build anything on top of it.

If both are present, the sign-in session is used.

## Sources

| Setting | Default | Effect |
|---|---|---|
| Google Chat | On | Process notifications from Google Chat, including Chat inside Gmail |
| Spaces to listen to | *(empty = all)* | Comma-separated space names. Matched case-insensitively against the notification's title and conversation name |
| Slack | On | Process notifications from the Slack app |
| Channels to listen to | *(empty = all)* | Comma-separated channel names, with or without `#` |
| Other apps | Off | Process PR links in notifications from any other app. Detection only — the app cannot react or reply there |

An empty allowlist includes direct messages. A non-empty one only matches
conversations whose notification text contains one of the entries.

## Repositories

| Setting | Default | Effect |
|---|---|---|
| GitHub owners | *(empty = any)* | Only PRs under these organizations or users are reviewed. **Set this** if you are in chats where links to third-party repositories are shared |
| Ignored repositories | *(empty)* | PRs in these repos are skipped. `repo` matches under any owner; `owner/repo` matches exactly |
| Related repositories | *(empty = off)* | For changes that span repositories: if the PR's branch name also has an open PR in one of these repos (same owner), the first 4,000 characters of its diff are added to the review as context |

Only `github.com` links of the form `https://github.com/<owner>/<repo>/pull/<n>` are
recognized. GitHub Enterprise Server hosts are not supported yet.

## Review behaviour

| Setting | Default | Effect |
|---|---|---|
| Claude model | `claude-opus-5-5` | Model id used for reviews. Any Messages API model id your account can use. A smaller model is cheaper and faster; the Status tab's **Test** checks the id is valid |
| Review language | English | Language the review text is written in. The verdict word, severities and rule codes stay in English |
| Approve clean PRs | On | *Autonomous mode.* Approve when the verdict is `APPROVE`, there are no merge conflicts and CI is not failing. Off = the app only reviews and comments |
| Merge after approving | On | *Autonomous mode.* Merge once CI has finished without failures. Requires the previous setting |
| Min / max delay | 120 / 300 s | *Autonomous mode.* The review starts a random time in this range after the notification. `0` / `0` = immediately |
| Wait for CI | 15 min | *Autonomous mode.* How long to wait for running checks before giving up on the merge (the PR stays approved) |
| Merge method | squash | `squash`, `merge` or `rebase`. Must be allowed in the repository's settings |
| Ignored CI checks | *(empty)* | Checks whose name contains any of these (case-insensitive) are ignored when computing the CI status. Use it for informational checks that often fail, e.g. `snyk, codecov` |

Review size: the diff is truncated to 24,000 characters and the response is capped at
8,192 tokens. These are not configurable yet.

## Messages and reactions

### Chat messages

Text the app posts in the thread of the PR message. **Leave a message empty to never
send it.**

| Message | Default | Sent when |
|---|---|---|
| Review left comments | `I left some comments on the PR` | The review was posted with findings. If the chat message linked several PRs, the PR URL is appended |
| Merge conflicts | `⚠️ This PR has merge conflicts and can't be merged until they are resolved.` | The PR was clean but cannot be merged |
| Approval failed | `⚠️ I couldn't approve the PR: {error}` | GitHub rejected the approval. `{error}` is replaced with the reason |

The app recognizes its own messages when they come back as notifications, by their
text, so that a reply never triggers another review. Keep them distinctive.

A failing CI is reported only through an Android notification, never in chat.

### Reactions

| Reaction | Default | Put on the message when |
|---|---|---|
| Reviewing | 👀 | The review starts |
| Approved | ✅ | The PR was merged by the app (replaces 👀). A PR that is approved but not merged keeps 👀 |
| Comments | 💬 | The review left comments (replaces 👀) |

Each value is either a literal emoji or a shortcode without colons — `eyes`,
`white_check_mark`, or the name of a custom emoji in your workspace. When one chat
message links several PRs, the "approved" reaction is added only once all of them have
been merged.

Google Chat's first reaction must be a standard emoji, so a custom emoji in
"Reviewing" falls back to 👀 there.

## Review rules

Configured in the **Rules** tab — see [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md).

| Setting | Default |
|---|---|
| Source | Built-in rules |
| Repository / branch / folder | *(empty)* |
| Custom text | *(empty)* |
| Extra instructions | *(empty)* |

## Storage keys

For contributors and for debugging. All values are strings in the `settings`
DataStore; lists are comma-separated.

| Key | Setting |
|---|---|
| `notification_analysis` | Listen for PR links (`enabled` / `disabled`) |
| `review_mode` | `autonomous` / `manual` |
| `source_google_chat`, `source_slack`, `source_other_apps` | Sources (`true` / `false`) |
| `chat_spaces`, `slack_channels` | Conversation allowlists |
| `filter_owners`, `filter_ignored`, `related_repos` | Repository filters |
| `review_model`, `review_language`, `merge_method` | Review behaviour |
| `auto_approve`, `auto_merge` | Autonomous approve / merge |
| `delay_min_sec`, `delay_max_sec`, `ci_wait_minutes` | Timing |
| `ci_ignored_checks` | Ignored CI checks |
| `emoji_working`, `emoji_approve`, `emoji_comment` | Reactions |
| `thread_msg`, `chat_msg_conflicts`, `chat_msg_approve_failed` | Chat messages (absent = default, blank = disabled) |
| `kb_source`, `kb_repo`, `kb_branch`, `kb_path`, `kb_extra_instructions` | Review rules |
| `github_token`, `anthropic_key`, `slack_token`, `anthropic_oauth_access`, `anthropic_oauth_refresh` | Credentials, encrypted (`enc:v1:…`) |

Defaults live in [`AppConfig`](../app/src/main/java/dev/pocketreviewer/config/AppConfig.kt).

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
