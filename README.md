# Pocket Reviewer

An Android app that watches your chat notifications for GitHub pull request links and
reviews them with Claude. When a teammate drops a PR link in Google Chat or Slack, the
app reacts to the message, reviews the diff against your rules, posts the review on
GitHub, and — if you let it — approves and merges clean PRs once CI passes.

It runs entirely on your phone, with your own credentials. There is no backend.

```
 PR link posted in Google Chat / Slack
              │  (Android notification)
              ▼
   👀  react to the message
              │
              ▼
   Claude reviews the diff against your rules
              │
      ┌───────┴────────┐
   clean             has findings
      │                 │
 approve, wait      post review on GitHub
 for CI, merge      reply in the thread
      │                 │
     ✅                 💬
```

## Features

- **Two chat sources**: Google Chat and Slack, each with an optional allowlist of
  spaces/channels. Any other app can be enabled as a detection-only source.
- **Two modes**: *autonomous* (does everything on its own after a short delay) and
  *manual* (you confirm each step — review, post, approve, merge — from the app).
- **Your rules**: a built-in generic rule set, markdown files from any GitHub repo, or
  text you paste. The target repo's `CLAUDE.md` / `AGENTS.md` is always included.
- **Everything is configurable**: which owners and repos to review, the Claude model,
  review language, merge method, delays, CI handling, every chat message and every
  reaction emoji. Nothing is tied to a particular organization.
- **Safe by default**: never merges with failing CI or merge conflicts; credentials are
  encrypted with the Android Keystore; a full history of what was done to each PR.

## Requirements

| What | Why | Required? |
|---|---|---|
| Android 8.0+ phone that receives your chat notifications | Detection works by reading notifications | Yes |
| GitHub personal access token | Read PRs, post reviews, approve, merge | Yes |
| Anthropic API key | Run the review | Yes |
| Google Cloud OAuth client + Google Workspace account | React and reply in Google Chat | Only for Google Chat reactions/replies |
| Slack user token | React and reply in Slack | Only for Slack reactions/replies |

Without the Google or Slack credentials the app still detects PR links from that
source and reviews them on GitHub; it just stays silent in the chat.

## Quick start

1. **Build and install** — open the project in Android Studio and press Run, or:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   Details in [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md).
2. Open the app. The **Status** tab shows a checklist of what is still missing.
3. Grant **notification access** from the checklist.
4. In **Settings → Accounts**, save a GitHub token and an Anthropic API key.
5. Optional: connect [Google Chat](docs/GOOGLE_CHAT.md) and/or [Slack](docs/SLACK.md).
6. Optional: in **Settings → Repositories**, restrict reviews to your GitHub owners.
7. The app starts in **manual mode**. Switch to autonomous once you trust the results.

To try it without any chat setup, paste a PR URL in the **Review** tab.

## Documentation

| Guide | Contents |
|---|---|
| [Getting started](docs/GETTING_STARTED.md) | Build, install, permissions, first review |
| [Google Chat setup](docs/GOOGLE_CHAT.md) | Creating the Google Cloud OAuth client |
| [Slack setup](docs/SLACK.md) | Creating the Slack app and user token |
| [Knowledge base](docs/KNOWLEDGE_BASE.md) | Choosing and writing the review rules |
| [Configuration reference](docs/CONFIGURATION.md) | Every setting, its default and its effect |
| [How it works](docs/HOW_IT_WORKS.md) | The review flow in both modes, step by step |
| [Architecture](docs/ARCHITECTURE.md) | Code structure, for contributors |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common problems and how to diagnose them |
| [Security](SECURITY.md) | What the app can do with your credentials |

## Good to know before you rely on it

- **It acts as you.** Reviews, approvals, merges, reactions and chat replies are made
  with your accounts. Read [SECURITY.md](SECURITY.md) before enabling autonomous mode.
- **It only sees what your phone is notified about.** Muted channels, notifications
  dismissed by another device, or a phone in battery saver mean missed PRs.
- **Reviews cost API usage.** Each review sends the diff (up to 24,000 characters) plus
  your rules to Claude. The rules are prompt-cached between reviews.
- **The model can be wrong.** A clean verdict is not a guarantee. Keep branch
  protection and required checks on the repositories you auto-merge.
- **Slack support is new** and has had less real-world use than Google Chat; see the
  known limitations in [docs/SLACK.md](docs/SLACK.md).

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Author

Created and maintained by **Carolina Sol Fernandez** —
[carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).

## License

[MIT](LICENSE) © 2026 Carolina Sol Fernandez
