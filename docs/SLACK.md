# Slack setup

Detecting PR links in Slack needs nothing but notification access. This guide is only
required if you want the app to **react to the message and reply in its thread**.

For that the app calls the Slack Web API with a **user token**: reactions and replies
appear as coming from you, the same way the Google Chat integration works. You get the
token by creating a small Slack app in your workspace.

## 1. Create a Slack app

1. Go to [api.slack.com/apps](https://api.slack.com/apps) → **Create New App** →
   **From scratch**. Pick any name and your workspace.
2. Open **OAuth & Permissions** and scroll to **Scopes → User Token Scopes**
   (not Bot Token Scopes). Add:

   | Scope | Used for |
   |---|---|
   | `reactions:write` | Add and remove reactions |
   | `chat:write` | Reply in the thread |
   | `search:read` | Find the message that contains the PR link |
   | `channels:history` | Read recent messages of public channels |
   | `groups:history` | …of private channels |
   | `im:history` | …of direct messages |
   | `mpim:history` | …of group direct messages |

   You can leave out the `*:history` scopes for conversation types you don't use.
3. Scroll up and press **Install to Workspace**. Depending on your workspace settings
   an admin may have to approve the app.
4. Copy the **User OAuth Token**. It starts with `xoxp-`.

## 2. Add the token to the app

**Settings → Accounts → Slack**, paste the token, press **Test** to verify it (Slack
answers with your user and workspace), then **Save**.

The token is encrypted on the device and is never displayed again.

## Choosing which channels to listen to

**Settings → Sources → Channels to listen to** takes a comma-separated list of channel
names, with or without the `#`. Leave it empty to listen everywhere, including direct
messages.

Matching is done against the text of the notification, case-insensitively, so it
depends on your Slack notification showing the channel name.

## Reactions and emoji

Slack identifies reactions by name. In **Settings → Messages and reactions** you can
use either:

- a shortcode — `eyes`, `white_check_mark`, or the name of one of your workspace's
  custom emoji such as `shipit`; or
- a literal emoji, as long as it is one the app knows the Slack name of (the common
  ones: 👀 ✅ ❌ 💬 🎉 🚀 👍 ⚠️ and a few dozen more).

If a reaction never appears, switch that setting to the shortcode.

The same settings are used for Google Chat, where shortcodes of standard emoji and
custom emoji names are also understood.

## How the message is found

1. If the notification reveals the channel id, the app scans that channel's 50 most
   recent messages for the PR URL.
2. Otherwise — or if the link was posted inside a thread, which channel history does
   not include — it uses Slack search for the exact URL, newest match first.

## Known limitations

Slack support is newer than the Google Chat integration. Please open an issue if
something does not behave as described.

- **Channel id detection is best effort.** Slack does not document how its Android
  notifications identify the conversation. When no id is found the app relies on
  search, which needs the `search:read` scope.
- **Search is not instant.** A message can take a little while to become searchable.
  The autonomous-mode delay (2–5 minutes by default) usually covers it; if you set the
  delay to zero and reactions are missing, raise it.
- **Same link posted twice.** Search returns the most recent message containing the
  URL, which may not be the one that triggered the notification.
- **You are not notified of your own messages**, so links you post yourself are not
  picked up. This is true of Google Chat as well.
- **Slack Connect and Enterprise Grid** workspaces may restrict user tokens or search
  across organizations.

## Revoking access

Remove the token in **Settings → Accounts → Slack → Remove**, and revoke it on the
Slack side under your app's **OAuth & Permissions → Revoke All OAuth Tokens** (or
uninstall the app from the workspace).

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
