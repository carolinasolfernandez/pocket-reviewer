# Getting started

This guide takes you from a fresh clone to your first review.

## 1. Build and install

You need a phone running Android 8.0 (API 26) or later.

The quickest way is to download the signed APK from the
[latest release](https://github.com/carolinasolfernandez/pocket-reviewer/releases/latest)
and open it on the phone (allow "install unknown apps" for your browser or file
manager when asked). Note that **Google Chat reactions will not work with an APK you
did not sign yourself** unless its signing certificate is registered in *your* Google
Cloud project — see [GOOGLE_CHAT.md](GOOGLE_CHAT.md). Everything else works.

To build it yourself:

### Option A — Android Studio

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** and select the project folder. The first Gradle sync downloads the
   dependencies and takes a few minutes.
3. Enable USB debugging on the phone (**Settings → About phone → tap "Build number"
   seven times**, then **Developer options → USB debugging**) and connect it.
4. Press **Run ▶**.

### Option B — command line

You need JDK 17 or newer and the Android SDK (platform 34, build-tools 34).

```bash
# Tell Gradle where the SDK is (once per clone)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # adjust the path

./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

If `./gradlew` reports that it cannot find Java, point `JAVA_HOME` at a JDK. Android
Studio bundles one:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

> **Keep the same signing key.** Google Sign-In is tied to the certificate the APK is
> signed with. Debug builds use `~/.android/debug.keystore`; if you build on another
> machine or switch to a release key, register that certificate's SHA-1 too
> (see [GOOGLE_CHAT.md](GOOGLE_CHAT.md)).

## 2. Grant permissions

Open the app and go to the **Status** tab. The checklist at the top shows what is
missing.

| Permission | How | Why |
|---|---|---|
| Notification access | **Grant notification access** in the checklist, then enable *Pocket Reviewer* | This is how PR links are detected |
| Show notifications | Accept the system prompt on first launch | Progress, results and manual-mode prompts |
| Unrestricted battery | **Turn off optimization** in the Service card | Lets autonomous reviews finish while the app is closed. Essential on Samsung, Xiaomi and other aggressive OEMs |

Also make sure Google Chat / Slack notifications themselves are enabled on the phone
for the conversations you care about.

## 3. Add credentials

**Settings → Accounts.** Credentials are encrypted on the device and never displayed
again after you save them.

### GitHub token

Create one at **GitHub → Settings → Developer settings → Personal access tokens**.

- **Fine-grained token** (recommended): select the repositories, then grant
  *Pull requests: Read and write* (post reviews, approve), *Contents: Read and write*
  (merge) and *Checks: Read* (CI status).
- **Classic token**: the `repo` scope.

If you only want reviews posted and will never approve or merge from the app, you can
leave *Contents* read-only.

GitHub does not let you approve your own pull requests, so the app can review them
but not approve them.

### Claude

Paste an Anthropic API key from [console.anthropic.com](https://console.anthropic.com).

The "Sign in with Claude" option below it is experimental — see the warning in
[CONFIGURATION.md](CONFIGURATION.md#claude-credentials).

### Chat accounts (optional)

- [Google Chat](GOOGLE_CHAT.md)
- [Slack](SLACK.md)

## 4. Check everything

On the **Status** tab press **Test**. It verifies the GitHub token, makes a one-token
request to the configured Claude model, and checks the Google and Slack connections.

## 5. Your first review

**Without chat:** open the **Review** tab, paste a PR URL and press the button. Nothing
is posted until you choose to.

**From chat:** post a PR link in a conversation that notifies your phone (or ask a
teammate to — most chat apps don't notify you about your own messages).

- In **manual mode** you get a notification; tap it to open the PR in **History** and
  run each step yourself.
- In **autonomous mode** the review starts after a random delay (2–5 minutes by
  default) and you are notified of the result.

The app starts in manual mode. Switch to autonomous (**Settings**, second switch) once
the reviews match what you would have written.

## Next steps

- Restrict which PRs are reviewed: [CONFIGURATION.md](CONFIGURATION.md#repositories)
- Teach it your conventions: [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md)
- Understand exactly what autonomous mode does: [HOW_IT_WORKS.md](HOW_IT_WORKS.md)

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
