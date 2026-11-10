# Contributing

Thanks for your interest in improving Pocket Reviewer.

The project is maintained by Carolina Sol Fernandez
([carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com)).

## Development setup

1. Clone the repository and open it in Android Studio, or build from the command line
   as described in [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md).
2. Run the unit tests:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
3. Build a debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before making structural changes.

## Ground rules

- **Nothing organization-specific in code.** No org, repo, channel, emoji or model
  names outside `AppConfig` defaults. If behaviour should differ between users, add a
  setting with a neutral default.
- **Keep decisions pure and tested.** Put logic in objects without Android
  dependencies and cover it with JVM tests under `app/src/test`. Android components
  should only gather inputs and carry out the result.
- **Chat actions go through `Messaging`.** Don't call a chat API directly from flow
  code.
- **User-visible text goes in string resources**, in English (`res/values/`) and
  Spanish (`res/values-es/`). Log lines, exception messages and anything sent to
  GitHub or the model stay in English.
- **Never log credentials or message contents** beyond what already is.
- **Update the docs** when you add or change a setting: `docs/CONFIGURATION.md` at
  minimum.

## Adding a setting

1. Add a field with its default to `AppConfig` and a key to `Keys`.
2. Read and write it in `ConfigStore.fromPrefs` / `ConfigStore.save`.
3. Add the control to the matching section of `SettingsScreen.kt`, and its strings to
   both `strings.xml` files.
4. Use it where needed via `ConfigStore.load(context)` or the listener's cached config.
5. Document it in `docs/CONFIGURATION.md`.

## Adding a chat platform

See "Adding a provider" in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Changing the built-in rules

Edit `app/src/main/assets/default_knowledge_base.md`. Keep the rules language- and
framework-agnostic, keep the rule codes stable, and keep it short: the whole file is
sent with every review.

## Pull requests

- Keep each PR to one change, with a description of what it does and how you tested
  it. Changes to detection, reactions or replies can only be verified on a device —
  say which app and Android version you tested with.
- Make sure the unit tests pass. CI runs the tests, lint and a debug build on every
  pull request.
- Do not include credentials, real chat content or private repository names in code,
  tests, screenshots or logs.

## Reporting bugs

Include the Android version and device, the chat source, the mode (manual or
autonomous), what you expected, what happened, and relevant Logcat lines
(`adb logcat -s PRListener ReviewManager GoogleChatClient SlackClient`) with tokens and
message contents removed.
