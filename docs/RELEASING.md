# Releasing

How to produce a signed release APK, locally or with GitHub Actions.

## Why signing matters here

Android only installs an update over an existing app if both are signed with the same
key, and Google Sign-In (used for Google Chat reactions) is tied to the signing
certificate. So: create **one** release key, keep it safe and backed up, and use it for
every release. If you lose it, users have to uninstall and reinstall, and you have to
register a new certificate in Google Cloud.

Debug builds (`assembleDebug`) are signed with the debug key of the machine that built
them and need none of this.

## 1. Create a release keystore (once)

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias pocket-reviewer \
  -keyalg RSA -keysize 2048 -validity 10000
```

Choose strong passwords. `*.jks` files are git-ignored; never commit the keystore or
its passwords.

## 2. Build a signed APK locally

Copy the example file and fill in your values:

```bash
cp keystore.properties.example keystore.properties
```

```properties
storeFile=release.jks        # relative to the repository root, or absolute
storePassword=…
keyAlias=pocket-reviewer
keyPassword=…
```

Then:

```bash
./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

Without `keystore.properties` (and without the environment variables below) the build
still succeeds but produces `app-release-unsigned.apk`, which cannot be installed.

Instead of the file you can use environment variables — this is what CI does:

| Variable | Meaning |
|---|---|
| `KEYSTORE_FILE` | Path to the keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |
| `VERSION_NAME` | Optional. Version shown to users (default `1.0.0`) |
| `VERSION_CODE` | Optional. Integer that must increase with every release (default `1`) |

`keystore.properties` takes precedence over the signing variables.

To check a build:

```bash
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

## 3. Register the release certificate for Google Chat

Google Chat reactions only work if an Android OAuth client exists for the package name
**and the SHA-1 of the key that signed the installed APK**. Get the release key's
SHA-1:

```bash
keytool -list -v -keystore release.jks -alias pocket-reviewer | grep SHA1
```

and add an Android OAuth client with it, as described in
[GOOGLE_CHAT.md](GOOGLE_CHAT.md#3-create-the-android-oauth-client). Keep the client for
your debug key too if you still install debug builds.

People who install *your* release APK use *your* signing certificate, so their Google
sign-in goes through your Google Cloud project: they must be allowed by its OAuth
consent screen (same Workspace organization for an Internal app, or listed as test
users / a verified app for External). Anyone who wants to be independent of that
builds and signs the app themselves.

## 4. Continuous integration

`.github/workflows/ci.yml` runs on every push to `main` and on every pull request:

1. unit tests (`:app:testDebugUnitTest`)
2. Android lint (`:app:lintDebug`)
3. a debug build (`:app:assembleDebug`)

The test and lint reports and the debug APK are attached to the run as artifacts. It
needs no secrets.

## 5. Publishing a release from GitHub Actions

`.github/workflows/release.yml` runs when a tag like `v1.2.0` is pushed. It runs the
tests, builds the signed release APK and app bundle (`.aab`), verifies the signature,
and publishes a GitHub release with both attached and auto-generated notes. The `.aab`
is what Google Play takes — see [PLAY_STORE.md](PLAY_STORE.md).

### One-time setup: repository secrets

In the repository: **Settings → Secrets and variables → Actions → New repository
secret**.

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The keystore, base64-encoded (see below) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias, e.g. `pocket-reviewer` |
| `KEY_PASSWORD` | Key password |

Encode the keystore:

```bash
base64 -i release.jks | pbcopy        # macOS: copies it to the clipboard
base64 -w 0 release.jks               # Linux: prints it
```

### Cutting a release

```bash
git tag v1.2.0
git push origin v1.2.0
```

The version comes from the tag: `v1.2.0` becomes version name `1.2.0` and version code
`10200` (`major × 10000 + minor × 100 + patch`). Keep minor and patch below 100, and
never reuse or go back to a lower version: Android refuses to install a lower version
code over a higher one.

A pre-release suffix is allowed in the name (`v1.3.0-beta1` → name `1.3.0-beta1`, code
`10300`).

If the workflow fails at "Restore keystore", the secrets are missing; if it fails at
"Verify the APK is signed", the alias or passwords do not match the keystore.

## Checklist for a release

- [ ] Tests pass on `main` (CI is green)
- [ ] User-facing changes are reflected in the docs
- [ ] The tag's version is higher than the previous release
- [ ] After publishing: install the APK over the previous version on a real phone and
      check that settings and credentials survived the update

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
