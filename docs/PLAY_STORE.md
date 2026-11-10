# Publishing on Google Play

Everything needed to publish Pocket Reviewer on Google Play: what is already prepared
in this repository, what you have to do in the Play Console, and the risks to know
about before you start.

Pocket Reviewer is an Android app, so the store is **Google Play**. It cannot be
published on Apple's App Store.

## What is already prepared

| Item | Where |
|---|---|
| Targets Android 16 (API 36), required by Google Play for new apps | `app/build.gradle.kts` |
| Signed app bundle (`.aab`) built by the release workflow | `.github/workflows/release.yml` |
| Privacy policy | [`PRIVACY.md`](../PRIVACY.md) |
| In-app disclosure shown before asking for notification access | Status screen |
| Store listing text, English and Spanish | [`play-store/listing.md`](play-store/listing.md) |
| App icon 512×512 | `play-store/graphics/icon-512.png` |
| Feature graphic 1024×500 | `play-store/graphics/feature-graphic-1024x500.jpg` |
| Phone screenshots, 9:16 | `play-store/screenshots/` |

## Before you start: things that can block or delay you

1. **New personal accounts must run a closed test first.** If your developer account
   is personal and was created after November 13, 2023, Google requires a closed test
   with **at least 12 testers opted in for 14 consecutive days** before you can apply
   for production. Plan for about three weeks. Organization accounts are exempt but
   need a D-U-N-S number.
2. **Google Chat reactions will not work for the general public.** They use Google
   Chat API scopes, which Google treats as sensitive: an OAuth client in "testing"
   mode is limited to 100 listed users, and lifting that requires Google's OAuth
   verification of your Cloud project (privacy policy, demo video, possibly a security
   assessment). Until then, Play users get detection and GitHub reviews, but Chat
   reactions only work for accounts you add as test users — or for people who build
   the app themselves. Slack is unaffected (each user brings their own token).
3. **The review may question some permissions.** See [Declarations](#5-app-content-declarations).
   The battery-optimization exemption in particular is restricted by policy and may
   have to be removed (the app works without it; autonomous reviews are just more
   likely to be interrupted).
4. **"Sign in with Claude" should not ship on Play.** It reuses the OAuth client of
   Anthropic's own tools and is not an approved integration; a store release makes
   that more visible. Recommended: remove it from the Play build and keep API keys
   only.
5. **It handles credentials and can merge code.** Expect questions in review; the
   listing and privacy policy already describe this plainly.

## 1. Create the developer account

1. Go to [play.google.com/console](https://play.google.com/console) and sign up
   ($25, one time).
2. Choose **Personal** or **Organization** (see point 1 above).
3. Complete identity verification. Google also asks you to verify a contact phone
   and, for personal accounts, that you have access to a real Android device.

## 2. Create the app

**Create app** → name `Pocket Reviewer`, default language English (United States),
type **App**, **Free**. Accept the declarations.

## 3. Build the bundle

Google Play takes an Android App Bundle (`.aab`), not an APK.

**With GitHub Actions** (recommended): push a version tag.

```bash
git tag v1.0.0
git push origin v1.0.0
```

The release workflow publishes `pocket-reviewer-1.0.0.aab` (and the APK) on the
[releases page](https://github.com/carolinasolfernandez/pocket-reviewer/releases).
Download the `.aab`.

**Locally:** with `keystore.properties` in place,

```bash
VERSION_NAME=1.0.0 VERSION_CODE=10000 ./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Every upload needs a higher version code than the previous one; the tag-based scheme
in [RELEASING.md](RELEASING.md) takes care of that.

### Play App Signing

On the first upload Google enrolls the app in **Play App Signing**: Google holds the
key that signs what users install, and your `release.jks` becomes the *upload key*.
Two consequences:

- Keep using the same `release.jks` for every upload.
- The certificate users' installs are signed with is **Google's**, not yours. For
  Google Chat sign-in, copy the **App signing key certificate SHA-1** from
  **Play Console → Test and release → App integrity → App signing** and add an Android
  OAuth client with it in your Google Cloud project (see
  [GOOGLE_CHAT.md](GOOGLE_CHAT.md#3-create-the-android-oauth-client)).

## 4. Store listing

**Grow users → Store presence → Main store listing.** Paste the texts from
[`play-store/listing.md`](play-store/listing.md) and upload the graphics from
`play-store/graphics/` and at least two screenshots from `play-store/screenshots/`.
Add the Spanish translation under **Manage translations**.

## 5. App content declarations

**Policy and programs → App content.** Suggested answers — read each one and make sure
you agree, since you are the one declaring it.

| Section | Answer |
|---|---|
| Privacy policy | `https://github.com/carolinasolfernandez/pocket-reviewer/blob/main/PRIVACY.md` |
| App access | Not all functionality is available without credentials. Explain that the app needs the reviewer's own GitHub token and Anthropic API key, and provide test credentials for the review team (a throwaway GitHub token with access to a test repository, and an API key with a small spending limit) |
| Ads | No ads |
| Content rating | Complete the questionnaire: utility/productivity, no user-generated content shared with other users, no violence, etc. Expected rating: Everyone |
| Target audience | 18 and over |
| News app, COVID-19, government app, financial features, health | No |
| Data safety | See below |
| Foreground service | Type **Data sync**. Justification: "Runs a user-requested code review to completion — fetching the pull request from GitHub, requesting the review, and posting the result — after the user leaves the app. The task is started by the user (manual mode) or by a notification the user configured the app to act on, and shows an ongoing notification." You will be asked for a short video showing the feature |

### Data safety

Google's form asks what the app *collects* (sends off the device) and *shares*. The
developer receives nothing; the app sends data only to services the user connects,
with the user's own credentials. A conservative way to fill it in:

- **Does your app collect or share any of the required user data types?** Yes.
- **Is all of the user data encrypted in transit?** Yes.
- **Do you provide a way to request data deletion?** Data is stored on the device and
  deleted by clearing app storage or uninstalling; there is no account.
- Data types: **Files and docs / Other user-generated content** — source code and
  review text sent to GitHub and Anthropic; **Messages → Other in-app messages** —
  chat messages read to locate the pull request link (Google Chat / Slack APIs).
  For each: collected, not shared with third parties for their own purposes,
  processed for **App functionality**, required.

How Google classifies data sent to a third-party API on the user's behalf is a matter
of interpretation; if in doubt, declare more rather than less.

### Sensitive permissions

| Permission | What to say |
|---|---|
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Core functionality: the app detects pull request links in the user's chat notifications. Disclosed in-app before the permission is requested and in the privacy policy; content is processed on-device |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional, user-initiated from the Status screen, so that a review the user started is not killed in the background. **If the review rejects it**, remove the permission from `AndroidManifest.xml` and the button from the Status screen and resubmit |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `INTERNET` | Standard; the foreground service declaration above covers the last two |

## 6. Testing track

1. **Test and release → Testing → Internal testing**: create a release, upload the
   `.aab`, add yourself as a tester and install from the opt-in link. Check that the
   app installs, that settings work, and — after adding the Play signing SHA-1 —
   Google sign-in.
2. **Closed testing** (required for new personal accounts): create a track, add at
   least 12 testers by email or Google Group, and share the opt-in link. They must
   stay opted in for 14 days in a row. Ask them to actually open the app; Google asks
   about tester engagement when you apply for production.

## 7. Production

**Test and release → Production → Apply for production access** (personal accounts)
or **Create new release**. Answer the questions about your closed test, choose the
countries, and submit. Review usually takes up to a week for a first release.

## After publishing

- Each update: push a new tag, download the `.aab`, upload it to a new release.
- Google raises the required target API level every August. Updating means bumping
  `compileSdk` / `targetSdk` in `app/build.gradle.kts` and testing on that Android
  version.
- Keep the store listing, `PRIVACY.md` and the Data safety form in step with what the
  app actually does.

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
