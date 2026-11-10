# Architecture

A single-module Android app (Kotlin, Jetpack Compose, coroutines, OkHttp, DataStore).
There is no backend and no dependency injection framework; components are Kotlin
`object`s with explicit `Context` parameters.

## Package layout

```
app/src/main/java/dev/pocketreviewer/
├── config/         Settings and credentials
│   ├── AppConfig.kt            AppConfig (every setting + default), Keys, ConfigStore
│   └── SecretStore.kt          Keystore-backed encryption of credentials
├── messaging/      Chat platforms behind one interface
│   ├── Messaging.kt            MessagingProvider interface + routing facade
│   ├── SlackClient.kt          Slack Web API provider, SlackNames
│   └── EmojiSpec.kt            emoji ⇄ shortcode conversion
├── api/            External services and the review itself
│   ├── ReviewManager.kt        Orchestrates a review; GitHub REST calls
│   ├── ReviewPrompt.kt         Builds the system/user prompt (pure)
│   ├── AnthropicAuth.kt        API key / OAuth credential handling
│   ├── GoogleChatClient.kt     Google Chat provider
│   ├── ChatMessages.kt         Catalog of texts written to chat
│   └── MergePolicy.kt          Mergeability rules (pure)
├── data/
│   ├── KnowledgeBaseRepository.kt   Rule sources, download, KbParsing (pure)
│   └── ReviewHistory.kt             History entries and their repository
├── service/        Android components and flow logic
│   ├── PRNotificationListener.kt    Detection + the autonomous flow
│   ├── NotificationSources.kt       Which app / conversation is accepted (pure)
│   ├── AutonomousDispatch.kt        Dedup and own-message rules (pure)
│   ├── MergeAggregator.kt           "All PRs of a message merged?" (pure)
│   ├── ManualFlow.kt                Manual stage machine (pure)
│   ├── ManualModeOrchestrator.kt    Runs one manual step
│   ├── ManualStepService.kt         Foreground service for manual steps
│   ├── ApproveService.kt            Foreground service for approve / merge actions
│   ├── ReviewKeepAliveService.kt    Keeps the process alive during autonomous runs
│   ├── ReviewWorker.kt              WorkManager path for notification actions
│   └── ReviewActionReceiver.kt      Receiver for notification actions
├── ui/             Compose screens
│   ├── MainActivity.kt         Host activity, tabs, SettingsActions implementation
│   ├── ReviewScreen.kt         On-demand review by URL
│   ├── HistoryScreen.kt        History and the manual flow
│   ├── StatusScreen.kt         Setup checklist, service state, connection test, log
│   ├── SettingsScreen.kt       Sectioned settings
│   └── KnowledgeBaseScreen.kt  Rules tab
└── util/
    ├── PullRequest.kt
    └── PrLinks.kt              PR link extraction and filters (pure)
```

Files marked *pure* have no Android dependencies and are covered by JVM unit tests.

## Design principles

**No organization-specific values in code.** Anything that differs between users is a
field of `AppConfig` with a neutral default, read through `ConfigStore`. If you find
yourself typing an org, repo, channel, emoji or model name into a `.kt` file outside
`AppConfig`, it belongs in the config.

**Pure logic is separated from Android.** Decisions (is this notification accepted? is
this PR filtered? what is the next manual stage?) live in small objects without
Android imports, so they can be tested on the JVM. Android components only gather
inputs and execute outcomes.

**Chat platforms are interchangeable.** The rest of the app never calls a chat API
directly; it goes through `Messaging`.

## Messaging abstraction

```kotlin
interface MessagingProvider {
    fun owns(resourceName: String): Boolean
    suspend fun isConnected(context: Context): Boolean
    suspend fun reactToPrMessage(context, prUrl, hintSpaceName, emojiSpec): String?
    suspend fun react(context, messageName, emojiSpec): String?
    suspend fun removeReaction(context, reactionName)
    suspend fun reply(context, resolvedMessageName, chatContext, text): Boolean
}
```

All providers use one naming scheme, so history entries, intents and the manual flow
carry plain strings without knowing the platform:

| | Google Chat | Slack |
|---|---|---|
| Space | `spaces/<id>` | `slack/<channelId>` (`slack/unknown` if not known) |
| Message | `spaces/<id>/messages/<id>` | `slack/<channelId>/messages/<ts>` |
| Thread | `spaces/<id>/threads/<id>` | `slack/<channelId>/threads/<ts>` |
| Reaction | `<message>/reactions/<id>` | `<message>/reactions/<emojiName>` |

`Messaging.providerFor(name)` routes on the prefix. `Messaging.messageOf(reaction)`
turns a reaction name back into its message name on any platform.

The key operation is `reactToPrMessage`: identifiers in an Android notification are not
reliable API identifiers on either platform, so each provider *searches* for the
message containing the PR URL and returns a handle to the real message. Every later
reaction and reply uses that resolved name, never the notification's.

### Adding a provider

1. Implement `MessagingProvider` as an `object` with its own name prefix.
2. Add it to `Messaging.providers`.
3. Teach `NotificationSources.classify` the app's package name, add a `Source` and an
   enable switch + allowlist in `AppConfig`.
4. In `PRNotificationListener.extractChatContext`, build a `ChatContext` from what the
   notification exposes.
5. Add the credential to `SecretStore`/`Keys` and a field to the Accounts section.

## Configuration flow

```
SettingsScreen ──► SettingsActions (MainActivity) ──► ConfigStore.save ──► DataStore
                                                                              │
PRNotificationListener ◄── ConfigStore.flow (collected, cached in a @Volatile) ◄┘
ReviewManager          ◄── ConfigStore.load (per review)
```

The listener never reads DataStore on the notification thread; it keeps the latest
`AppConfig` snapshot from a collector.

Credentials go through `SecretStore`: AES-256-GCM with a non-exportable Android
Keystore key, stored as `enc:v1:<base64(iv‖ciphertext)>`. Values without the prefix are
treated as legacy plaintext on read.

## Review pipeline

`ReviewManager` exposes the steps separately so both modes can compose them:

| Function | Used by |
|---|---|
| `analyzeOnly` | Manual flow, Review tab — review without posting |
| `postAnalysis` | Manual flow — post an existing review |
| `runReview` | Review + post if there are findings |
| `runReviewFull` | Autonomous flow — reactions + `runReview` + thread reply |
| `approvePR`, `mergeOnly`, `approveAndMergePR` | Both |
| `fetchCiStatus`, `fetchHasConflicts` | Both |
| `swapReaction` | Replace the current reaction by the one for a verdict |

The prompt has two halves (`ReviewPrompt`):

- **system** — instructions, knowledge base, severities, response format. Depends only
  on configuration, so it is identical across PRs and sent with `cache_control` (1 h).
- **user** — repo rules and the PR itself.

Keep PR-specific text out of the system half, or caching stops working.

GitHub list endpoints are paginated by following the `Link` header; GitHub and
Anthropic calls are retried with backoff on 429/5xx.

## Autonomous flow and concurrency

`PRNotificationListener` owns a coroutine scope tied to the listener's lifetime. Per
notification it launches one coroutine per PR; an `inFlightAutonomous` set prevents two
concurrent reviews of the same PR, and one `MergeAggregator` per notification decides
when all PRs of a message have been merged. `ReviewKeepAliveService` holds a foreground
service for the duration.

The manual flow is stateless: `ManualModeOrchestrator` reads the history entry, runs
one step, and writes the next stage back. That is what lets a step survive process
death and be resumed from the History tab.

## Internationalization

UI and notification text lives in `res/values/strings*.xml` (English) with Spanish in
`res/values-es/`. Text sent to GitHub or to the model, log lines and exception
messages are English and not localized. The *review itself* is written in the language
configured in Settings, independent of the device locale.

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

JVM unit tests cover the pure logic. There are no instrumented tests: behaviour that
depends on real notifications, the Chat/Slack APIs or the Keystore has to be verified
on a device.

---

*Pocket Reviewer is created and maintained by Carolina Sol Fernandez — [carolinasolfernandez@gmail.com](mailto:carolinasolfernandez@gmail.com).*
