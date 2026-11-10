# Privacy policy

**Pocket Reviewer** — last updated: November 10, 2026

Pocket Reviewer is an open-source Android app created by Carolina Sol Fernandez
(contact: [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com)).
This policy explains what data the app handles and where it goes.

## Summary

- The app has **no backend**. The developer does not receive, store or have access to
  any of your data.
- There is **no advertising, analytics or tracking** of any kind.
- Everything the app stores stays **on your device**.
- The app talks only to the services **you** connect, using **your own** credentials:
  GitHub, Anthropic, and optionally Google Chat and Slack.

## Data the app accesses

### Notifications

With your permission ("notification access"), the app reads notifications posted on
your device in order to find links to GitHub pull requests.

- Android grants this access for all notifications. The app only processes those from
  the sources you enable in Settings (Google Chat, Slack, and — only if you turn it
  on — other apps) and discards the rest immediately.
- Notification content is processed **on the device** and is **not stored**. The only
  thing kept from a notification is the pull request link it contained, plus the
  identifiers needed to react or reply to that message.
- For troubleshooting, the title and text of chat notifications are written to the
  device's system log and a short line is kept in memory for the Status screen. This
  information does not leave the device.
- You can revoke notification access at any time in Android settings.

### Credentials

The GitHub token, Anthropic API key, Slack token and (if used) Claude sign-in tokens
that you enter are encrypted with the Android Keystore and stored only on your device.
Google sign-in is handled by Google Play services.

### Review history and settings

Your settings, the rules you configure, and the history of reviewed pull requests
(including the review text) are stored in the app's private storage on your device.
Device backup is disabled for the app.

## Data sent to third parties

The app sends data only to the following services, only to perform the function you
set it up for, and always authenticated as you:

| Service | What is sent | Why |
|---|---|---|
| **GitHub** (`api.github.com` or your GitHub Enterprise Server) | Requests for pull request data; the review text; approvals and merges | To read, review, approve and merge pull requests |
| **Anthropic** (`api.anthropic.com`) | Your review rules, and the pull request's title, description, diff, file list, existing review comments and the repository's `CLAUDE.md` / `AGENTS.md` | To generate the review |
| **Google Chat** (`chat.googleapis.com`), if connected | Requests to list your spaces and recent messages; reactions; thread replies | To find the message with the PR link, react to it and reply |
| **Slack** (`slack.com`), if connected | Requests to read recent messages or search for the PR link; reactions; thread replies | Same as above |

Each of these services handles the data under its own terms and privacy policy. In
particular, source code from the pull requests you review is sent to Anthropic; make
sure that is acceptable for the code you work with.

The app does not send data to any other party.

## Data retention and deletion

All data is on your device. You can delete it at any time by clearing the history in
the app, removing credentials in Settings, clearing the app's storage in Android
settings, or uninstalling the app. Data already sent to GitHub, Anthropic, Google or
Slack is governed by those services.

## Children

The app is a developer tool and is not directed at children.

## Changes

Changes to this policy are published in this file; its history is public in the
[source repository](https://github.com/carolinasolfernandez/pocket-reviewer).

## Contact

Questions or requests: [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com)
