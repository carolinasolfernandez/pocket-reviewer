# Google Chat setup

Detecting PR links in Google Chat needs nothing but notification access. This guide is
only required if you want the app to **react to the message and reply in its thread**.

Those actions go through the Google Chat API on your behalf, and Google requires every
app that does this to have its own OAuth client. Because this project is distributed as
source, **you create that client yourself**, once. It takes about ten minutes.

## Requirements

- A **Google Workspace** account. The Chat API is not available to personal
  `@gmail.com` accounts.
- Permission to create a Google Cloud project, or a Workspace admin who can do these
  steps for you.

## 1. Create a Google Cloud project

1. Go to [console.cloud.google.com](https://console.cloud.google.com) and create a
   project (any name).
2. **APIs & Services → Library**, search for **Google Chat API**, and enable it.
3. On the Chat API page open **Configuration** and fill in the required fields (app
   name, avatar URL, description). Google requires this even for apps that only act as
   a user. You do not need to enable any interactive features.

## 2. Configure the OAuth consent screen

**APIs & Services → OAuth consent screen**

- **User type**: *Internal* if everyone who will use the app is in your Workspace
  organization (simplest — no verification, no test users). Otherwise *External*, and
  add each user under **Test users**.
- **Scopes**: add

  | Scope | Used for |
  |---|---|
  | `https://www.googleapis.com/auth/chat.messages` | Find the message with the PR link; post the thread reply |
  | `https://www.googleapis.com/auth/chat.messages.reactions.create` | Add reactions |
  | `https://www.googleapis.com/auth/chat.spaces.readonly` | List your spaces to locate the message |
  | `https://www.googleapis.com/auth/chat.customemojis.readonly` | *Optional.* Use your workspace's custom emoji as reactions |

  The custom emoji scope is best-effort: if you leave it out, reactions fall back to
  standard emoji.

## 3. Create the Android OAuth client

**APIs & Services → Credentials → Create credentials → OAuth client ID**

- **Application type**: Android
- **Package name**: `dev.pocketreviewer`
  (if you changed `applicationId` in `app/build.gradle.kts`, use your value)
- **SHA-1 certificate fingerprint**: the fingerprint of the key your APK is signed with.

  For debug builds:
  ```bash
  ./gradlew signingReport
  ```
  and copy the `SHA1` of the `debug` variant, or:
  ```bash
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
          -storepass android -keypass android | grep SHA1
  ```

There is nothing to download or paste into the app: Google matches the installed APK's
package name and signature against this client.

If you build on more than one machine, or ship a release build, add one Android client
per signing certificate.

## 4. Connect in the app

**Settings → Accounts → Google Chat → Connect Google account**, pick your Workspace
account and accept the permissions. The **Status** tab should now show the Google Chat
account as connected.

## Choosing which spaces to listen to

**Settings → Sources → Spaces to listen to** takes a comma-separated list of space
names. Leave it empty to listen everywhere, including direct messages.

Matching is done against the text of the notification (its title and conversation
name), case-insensitively, so a partial name such as `review` matches "Code review".

## How the message is found

The identifiers in an Android notification are not the ones the Chat API uses, so the
app does not trust them. Instead it lists the recent messages of your spaces (starting
with the one the notification hints at) and looks for the one containing the PR URL.
Reactions and replies are then made on that real message.

This means the reaction can take a few seconds if you are in many spaces, and that a
link posted long before the review ran (more than the 25 most recent messages of its
space) will not be found — the review still happens on GitHub.

## Troubleshooting

| Symptom | Cause |
|---|---|
| The account picker closes and nothing happens; Logcat shows `statusCode=10` | No Android OAuth client matches this package name + SHA-1. Re-check step 3 with the certificate of the APK that is actually installed |
| `statusCode=12500` | Consent screen is incomplete, or you are not a test user of an External app |
| Connected, but no reactions | The Chat API is not enabled or not configured (step 1.3), or a scope is missing from the consent screen |
| Reactions work but custom emoji don't | The `chat.customemojis.readonly` scope was not granted; disconnect and connect again after adding it |
| "Access blocked: app not verified" | External app in testing mode and your account is not listed under Test users |

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
