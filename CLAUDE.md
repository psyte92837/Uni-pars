# Uni-pars — notes for AI assistants

Android app: AI (user's key, Gemini by default) writes a declarative **recipe** once; the app then
parses in the background **without AI** and notifies. Read `docs/CODEMAP.md` before opening code —
it says which file does what, so you rarely need to read more than 1–2 files.

## Rules
- **Branch: work and push only to `main`.** No feature branches, no PRs unless asked.
- CI builds the APK on every push to `main` (`.github/workflows/android.yml`). Locally there is
  no Android SDK/AGP (dl.google.com is blocked in cloud sessions) — verify JVM modules locally,
  the app module in CI.
- User-visible strings: add them to `scripts/strings_source.py` (ru + en) and run it; it regenerates
  `res/values{,-en}/strings.xml` and prints missing/unused keys. Don't edit the XML by hand.
- Room schema change → bump `AppDatabase` version + add a `Migration` (see `MIGRATION_1_2`).
- Recipe JSON (`engine/Model.kt`) is persisted and exported: add fields only with defaults.
- New dependency used via reflection (Jackson etc.) → check `app/proguard-rules.pro` (R8 is on for release).
- Code comments: English, explain *why*. UI/notification text: Russian + English resources.
- Commit trailer lines are given by the session; never put model ids in commits.

## Commands
```bash
scripts/jvm-check.sh            # engine + ai unit tests locally (no Android SDK needed)
scripts/jvm-check.sh :engine:test   # one module
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug   # CI / machine with SDK
```
CI status/logs: GitHub MCP `actions_list` (workflow `android.yml`, branch `main`) → `get_job_logs`.
APK = artifact `uni-pars-apk-<run>`; tag `v1.2.3` → GitHub Release with APKs.

## Stack (versions in `gradle/libs.versions.toml`)
Kotlin 2.0.21, AGP 8.11.1, Gradle 8.14.3, compile/target SDK 36, min 26, Compose (BOM), Room 2.6 + KSP,
WorkManager 2.10, OkHttp 4.12, Jsoup, kotlinx.serialization, Anthropic Java SDK 3.2.0 (Claude),
Gemini via REST (`gemini-3.5-flash-lite`). Tests: JUnit4, MockWebServer, Robolectric (sdk 34).

## Gotchas already solved (don't re-investigate)
- `setup-android` must get explicit `packages:` (the old `tools` package no longer exists).
- R8 missing `java.lang.reflect.AnnotatedType` from Anthropic SDK → `-dontwarn` in proguard-rules.
- Maven Central sometimes answers 429 locally → `jvm-check.sh` retries.
- Gemini `responseJsonSchema` rejected → `GeminiClient` falls back to schema-in-prompt automatically.
- Background interval < 15 min is impossible with WorkManager; don't add exact alarms.
