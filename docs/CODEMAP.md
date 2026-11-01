# Code map

One line per file. Paths are under `<module>/src/main/kotlin/com/unipars/<module>/`.

## engine — pure Kotlin/JVM, no Android, fully unit-tested
| File | Responsibility |
|---|---|
| `Model.kt` | Recipe, Source, Extract, FieldSpec, Trigger, FetchSettings (user options), SiteProfile, Item; `EngineJson` |
| `Extractor.kt` | Apply `Extract` to a page: CSS (Jsoup) / JSON (body, JSON-LD, script), fallbacks, transforms, number parsing, item keys (sha1) |
| `JsonPath.kt` | Minimal JSON path (`$.a[*].b`, `..x`, filters `[?(@.k=='v')]`) |
| `Validator.kt` | Detects a broken recipe (minItems, required fields fill ratio) |
| `Differ.kt` | Previous vs current items → events filtered by triggers (new/changed/removed/below/above/contains); first run = baseline |
| `RecipeRunner.kt` | One background run: fetch (via `PageFetcher`) → block check → extract + pagination → validate → diff → `RunOutcome` |
| `BlockDetector.kt` | Captcha / Cloudflare-style challenge / 429 / login redirect detection |
| `SiteProbe.kt` | Local site facts (anti-bot vendors, robots, JSON-LD, feeds, SPA) + conservative recommended settings; `Robots` parser |
| `HtmlCleaner.kt` | Shrinks HTML for the AI, masks personal data |
| `Safety.kt` | Regex time budget, same-site check, PII masking, clipping |
| `Schedule.kt` | Effective interval, quiet hours, backoff |

## ai — recipe generation (JVM, used only during setup / explicit repair)
| File | Responsibility |
|---|---|
| `AiClient.kt` | Provider-neutral request/response/usage/errors |
| `AiProvider.kt` | GEMINI (default) / CLAUDE, default models, factory |
| `GeminiClient.kt` | REST `generateContent`: JSON schema output (+fallback), Google Search grounding, error mapping |
| `ClaudeClient.kt` | Anthropic Java SDK, structured output, web search, server-side fallback |
| `Prompts.kt` | System prompts; page data wrapped in `<untrusted_page_data>` |
| `Drafts.kt` | Model output schema (`DraftSchema`) and mapping to engine types with clamping |
| `SetupAssistant.kt` | research → draft → local verify → retry (≤3); repair; flags foreign sources |
| `SiteAssistant.kt` | Home-screen helper: plan (help / site given / find sites) → web search → structured suggestions; drops hosts absent from search results |
| `Pricing.kt` | Approximate $ per 1M tokens for the cost estimate |

## app — Android
| File | Responsibility |
|---|---|
| `UniParsApp.kt` | Service locator (db, repository, prefs, notifier), theme `StateFlow` + `updateTheme`, `fetcherFactory` (swapped in tests), crash log |
| `data/Database.kt` | Room entities/DAOs (recipes, items, events, runs, profiles), migrations |
| `data/Repository.kt` | Save recipe+profile, previous items, store run results |
| `data/SecurePrefs.kt` | Keystore-encrypted API keys, provider/model/effort, budget/spend, flags |
| `data/Transfer.kt` | Recipe export/import JSON, items CSV |
| `net/HttpFetcher.kt` | OkHttp with conditional GET, retries, WebView-shared cookies & UA |
| `net/WebViewFetcher.kt` | Offscreen WebView render for `webview` recipes |
| `net/AppFetcher.kt` | Chooses HTTP vs WebView |
| `setup/SetupEngine.kt` | Setup pipeline glue: client creation (key/budget), probe, research, verify, repair, cost |
| `work/ParseWorker.kt` | Background run of one recipe; statuses, notifications, foreground for WebView |
| `work/Scheduler.kt` | Unique periodic work per recipe, run-now |
| `work/DomainGate.kt` | Per-domain serialization and minimum gap |
| `work/MaintenanceWorker.kt` | Daily: retention, robots re-check, delay warning, update check |
| `notify/Notifier.kt` | Channels and all notifications |
| `update/UpdateChecker.kt` | GitHub Releases latest-version check |
| `widget/StatusWidget.kt` | Home-screen widget |
| `ui/MainActivity.kt` | Nav graph, share-intent → `new?url=`, theme, `formatTime` |
| `ui/RecipeListScreen.kt` | List, export/import menu, `statusText`, hosts the assistant panel |
| `ui/AssistantPanel.kt` | AI assistant panel (expanded/collapsed/hidden), suggestions → setup wizard (`new?url=&goal=`) |
| `ui/NewRecipeScreen.kt` | Setup wizard (URL/goal → WebView picker → AI → review/save) |
| `ui/RecipeDetailScreen.kt` | Data/events/log/settings tabs, CSV, repair with AI |
| `ui/SettingsScreen.kt` | Provider, keys, model, budget, battery, updates, crash report |
| `ui/CheckScreen.kt` | Human passes captcha/login in WebView, then resume |
| `ui/PageWebView.kt` | WebView composable + JS bridge (element picker, fetch/XHR JSON capture) |
| `ui/Editors.kt` | Settings and triggers editors |
| `ui/Permissions.kt` | Notification permission request (once, or forced in onboarding/settings) |
| `ui/OnboardingScreen.kt` | First-run wizard: welcome → AI key → appearance → notifications (permission pops up) → background → done |
| `ui/SetupParts.kt` | Reusable sections: `AiSetupSection` (provider, key links, paste, test), `AppearanceSection`, `NotificationsSection`, `BackgroundSection` |
| `ui/theme/Theme.kt` | `UniParsTheme`: ThemeMode (system/light/dark), gradient `Palette`s, Material You option, shapes, `LocalAppGradient` |
| `ui/theme/Components.kt` | `AppScaffold` (gradient top bar), `SectionCard`, `Monogram`, `StatusPill` — use these for new screens |

## Flow in one paragraph
Setup: `NewRecipeScreen` → `SetupEngine.probe` (no AI) → optional `research` → `SetupAssistant.createRecipe`
(AI draft verified on the real page) → user edits settings/triggers → `Repository.saveNewRecipe` + `Scheduler`.
Background: `ParseWorker` → `DomainGate` → `RecipeRunner` → `Repository.storeRun` → `Notifier`.
Problems: captcha → `NEEDS_HUMAN` + notification → `CheckScreen`; layout change → `BROKEN` → "Repair with AI".
