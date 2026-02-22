# MyApplication

## 1. Project Overview

**Purpose:** Android chat application for comparing LLM responses across multiple restriction profiles side by side.

**Core idea:** A single user input is broadcast simultaneously to an unrestricted chat pane and up to four configurable restriction profile panes. Each pane sends the message independently to an OpenAI-compatible API with its own system prompt, token limit, temperature, model, and prompt-generation mode. Responses appear in parallel in a split-screen layout. Each assistant response shows a cost/stats line with token counts, latency, and price in both USD and RUB.

---

## 2. Architecture

### High-level layers

```
Presentation  →  Domain  →  Data
    │                          │
ChatScreen               AnthropicApi (Retrofit)
ChatViewModel            ChatRepositoryImpl
SettingsDialog           SettingsRepositoryImpl
```

### Component responsibilities

| Component | Responsibility |
|---|---|
| `MainActivity` | Single-activity host; sets up Compose content tree |
| `MyApp` | Application class; initializes Koin DI |
| `ChatScreen` | Renders split-screen pane layout, top bar, message input, snackbar, per-message meta row |
| `ChatViewModel` | Owns `ChatUiState`; orchestrates parallel message dispatch; applies settings; fetches available models and USD→RUB exchange rate on startup |
| `SettingsDialog` | Form UI for editing `Settings` and `RestrictionProfile` list; includes model selector populated from `GET /v1/models` |
| `SendMessageUseCase` | Thin delegation layer from domain to data |
| `ChatRepository` / `ChatRepositoryImpl` | Converts domain messages to API request; parses response; measures wall-clock latency; extracts token usage |
| `SettingsRepository` / `SettingsRepositoryImpl` | Persists restriction profiles via `SharedPreferences` + Gson |
| `AnthropicApi` | Retrofit interface; `POST /responses` and `GET /models` endpoints |

### Component interactions

```
User input
    └─► ChatViewModel.sendMessage()
            ├─► [unrestricted pane] sendDirect() or sendWithPromptGeneration()
            └─► [each profile pane] sendDirect() or sendWithPromptGeneration()
                        └─► SendMessageUseCase.invoke()
                                └─► ChatRepositoryImpl.sendMessage()
                                        └─► AnthropicApi.sendMessage()
                                                └─► POST https://api.proxyapi.ru/openai/v1/responses
                                        └─► returns Pair<String, MessageMeta>
```

Settings flow:
```
SettingsDialog → onSave callback → ChatViewModel.saveSettings()
    ├─► SettingsRepositoryImpl.saveSettings()  (persists profiles to SharedPreferences)
    └─► _uiState.update()  (rebuilds pane list; retains existing message history by index)
```

Model fetch flow (on settings dialog open):
```
ChatViewModel.showSettingsDialog()
    └─► AnthropicApi.getModels()  →  GET /v1/models
            └─► filters non-text models (image/audio/tts/etc.)
            └─► _uiState.update { availableModels = ... }
```

Exchange rate flow (on app start):
```
ChatViewModel.init
    └─► OkHttpClient  →  GET https://www.cbr-xml-daily.ru/daily_json.js
            └─► parses Valute.USD.Value
            └─► _uiState.update { usdToRub = ... }
```

---

## 3. Core Logic

### Message dispatch

`ChatViewModel.sendMessage()` does the following in order:

1. Appends the user message to all pane states and sets loading flags.
2. Launches a coroutine for each pane (unrestricted + each profile) in parallel.
3. Each coroutine calls either `sendDirect` or `sendWithPromptGeneration` depending on the profile's `generatePromptFirst` flag.
4. The selected model for each pane is passed through the entire call chain down to `ChatRequest.model`.

### Send modes

**`sendDirect`**
- Reads current message history for the pane.
- Calls `SendMessageUseCase` with `instructions`, `maxOutputTokens`, `temperature`, and `model`.
- Appends the assistant response (with `MessageMeta`) to the pane; clears loading flag.

**`sendWithPromptGeneration`** (two-step)
1. Replaces the last user message with a meta-prompt: `"Create prompt for LLM model with next question: <original>"`.
2. Sends the meta-prompt to the API → receives a generated prompt.
3. Appends the generated prompt as both an assistant message and a new user message.
4. Sends the generated prompt to the API → receives the final response (with `MessageMeta`).
5. Appends the final response; clears loading flag.

### Instructions assembly (`buildInstructions`)

Combines `responseFormatDescription` and a stop-sequence instruction into a single system instructions string. Either or both fields may be empty; `null` is passed if the result is blank.

### Response metadata (`MessageMeta`)

Every assistant message carries a `MessageMeta` object:

| Field | Source |
|---|---|
| `inputTokens` | `usage.input_tokens` from API response |
| `outputTokens` | `usage.output_tokens` from API response |
| `durationMs` | Wall-clock time measured around the `api.sendMessage()` call |
| `model` | Model ID used for the request |

Displayed below each assistant bubble as:
```
↑<input> ↓<output> · <total>tok · <duration> · $<usd> · ₽<rub>
```

### Cost estimation

USD cost is calculated using per-model pricing (per 1M tokens):

| Model | Input ($/1M) | Output ($/1M) |
|---|---|---|
| gpt-4o-mini | 0.15 | 0.60 |
| gpt-4o | 2.50 | 10.00 |
| gpt-4-turbo | 10.00 | 30.00 |
| gpt-4 | 30.00 | 60.00 |
| gpt-3.5 | 0.50 | 1.50 |
| o1-mini | 1.10 | 4.40 |
| o1 | 15.00 | 60.00 |
| o3-mini | 1.10 | 4.40 |
| o3 | 10.00 | 40.00 |

RUB cost = USD cost × live USD/RUB rate fetched from CBR on app start (fallback: 90.0).

### Model selection

When the settings dialog opens, `GET /v1/models` is called and the returned list is filtered to remove non-text-generation models. Excluded if the model ID matches (case-insensitive):

`image | audio | tts | transcribe | realtime | moderation | embedding | sora | whisper | dalle | search`

The filtered, sorted list is shown as a dropdown in each profile card and in the unrestricted profile card.

### Lesson 3 preset (`saveSettings`)

When `Settings.createLesson3Chats == true`, `saveSettings` replaces the profile list with four fixed profiles before persisting:

| # | Name | Key setting |
|---|---|---|
| 1 | Unrestricted | No system prompt, no restrictions |
| 2 | Step by step | System prompt: "Solve this task step by step" |
| 3 | Generate prompt first | `generatePromptFirst = true` |
| 4 | Multi-role | System prompt with Analytic / Engineer / Critic roles |

The flag is reset to `false` after application.

---

## 4. Tech Stack

| Category | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose (Material 3) |
| Architecture | MVVM + Clean Architecture (domain / data / presentation) |
| Dependency injection | Koin |
| Networking | Retrofit 2 + OkHttp 3 |
| JSON serialization | Gson + `org.json.JSONObject` (for CBR rate parsing) |
| Async | Kotlin Coroutines + `StateFlow` |
| Persistence | `SharedPreferences` |
| API backend | OpenAI-compatible proxy (`api.proxyapi.ru`) |
| Exchange rate | CBR JSON feed (`cbr-xml-daily.ru/daily_json.js`) |

---

## 5. Limitations & Assumptions

- **API key is hardcoded** in `AppModule.kt`. There is no secure storage or runtime configuration.
- **Settings persistence is partial.** `SettingsRepositoryImpl` only saves and loads `profiles`. The fields `unrestrictedGeneratePromptFirst`, `unrestrictedTemperature`, `unrestrictedModel`, and `createLesson3Chats` are held in memory only and reset to defaults on every app restart.
- **No message persistence.** All chat history is in-memory; restarting the app clears all messages.
- **Pane identity is index-based.** When profiles are reordered or the list length changes, existing message history is matched by position, not by profile identity. Messages may shift to the wrong pane.
- **Stop sequences are appended to instructions text**, not passed as a dedicated API parameter. Effectiveness depends on the model following the instruction.
- **Max 4 restriction profiles** enforced only in the UI; the domain model has no hard limit.
- **Single error snackbar** is shared across all panes. Concurrent errors from multiple panes overwrite each other.
- **Temperature options are fixed** to `[0.0, 0.7, 1.0, 1.2]`; free-form input is not supported.
- **No retry logic.** Failed requests surface a snackbar and drop the response; the loading state is cleared.
- **`generatePromptFirst` in two-step mode** reads pane state between step 1 and step 2 from `_uiState.value` directly, which is a snapshot that could be stale if concurrent updates occur.
- **Cost estimates are approximations.** Prices are hardcoded and may drift from actual billing. The CBR rate is fetched once at startup and not refreshed during the session.
- **Model list is fetched on every settings dialog open** but not cached across opens; a failed fetch leaves the dropdown empty (falls back to showing the currently saved model only).
