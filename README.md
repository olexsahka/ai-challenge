# MyApplication

## 1. Project Overview

**Purpose:** Android chat application for comparing LLM responses across multiple restriction profiles side by side.

**Core idea:** A single user input is broadcast simultaneously to an unrestricted chat pane and up to four configurable restriction profile panes. Each pane sends the message independently to an OpenAI-compatible API with its own system prompt, token limit, temperature, and prompt-generation mode. Responses appear in parallel in a split-screen layout.

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
| `ChatScreen` | Renders split-screen pane layout, top bar, message input, snackbar |
| `ChatViewModel` | Owns `ChatUiState`; orchestrates parallel message dispatch; applies settings |
| `SettingsDialog` | Form UI for editing `Settings` and `RestrictionProfile` list |
| `SendMessageUseCase` | Thin delegation layer from domain to data |
| `ChatRepository` / `ChatRepositoryImpl` | Converts domain messages to API request; parses response |
| `SettingsRepository` / `SettingsRepositoryImpl` | Persists restriction profiles via `SharedPreferences` + Gson |
| `AnthropicApi` | Retrofit interface; single `POST /responses` endpoint |

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
```

Settings flow:
```
SettingsDialog → onSave callback → ChatViewModel.saveSettings()
    ├─► SettingsRepositoryImpl.saveSettings()  (persists profiles to SharedPreferences)
    └─► _uiState.update()  (rebuilds pane list; retains existing message history by index)
```

---

## 3. Core Logic

### Message dispatch

`ChatViewModel.sendMessage()` does the following in order:

1. Appends the user message to all pane states and sets loading flags.
2. Launches a coroutine for each pane (unrestricted + each profile) in parallel.
3. Each coroutine calls either `sendDirect` or `sendWithPromptGeneration` depending on the profile's `generatePromptFirst` flag.

### Send modes

**`sendDirect`**
- Reads current message history for the pane.
- Calls `SendMessageUseCase` with `instructions`, `maxOutputTokens`, and `temperature`.
- Appends the assistant response to the pane; clears loading flag.

**`sendWithPromptGeneration`** (two-step)
1. Replaces the last user message with a meta-prompt: `"Create prompt for LLM model with next question: <original>"`.
2. Sends the meta-prompt to the API → receives a generated prompt.
3. Appends the generated prompt as both an assistant message and a new user message.
4. Sends the generated prompt to the API → receives the final response.
5. Appends the final response; clears loading flag.

### Instructions assembly (`buildInstructions`)

Combines `responseFormatDescription` and a stop-sequence instruction into a single system instructions string. Either or both fields may be empty; `null` is passed if the result is blank.

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
| JSON serialization | Gson |
| Async | Kotlin Coroutines + `StateFlow` |
| Persistence | `SharedPreferences` |
| API backend | OpenAI-compatible proxy (`api.proxyapi.ru`) with model `gpt-4o` |

---

## 5. Limitations & Assumptions

- **API key is hardcoded** in `AppModule.kt`. There is no secure storage or runtime configuration.
- **Settings persistence is partial.** `SettingsRepositoryImpl` only saves and loads `profiles`. The fields `unrestrictedGeneratePromptFirst`, `unrestrictedTemperature`, and `createLesson3Chats` are held in memory only and reset to defaults on every app restart.
- **No message persistence.** All chat history is in-memory; restarting the app clears all messages.
- **Pane identity is index-based.** When profiles are reordered or the list length changes, existing message history is matched by position, not by profile identity. Messages may shift to the wrong pane.
- **Stop sequences are appended to instructions text**, not passed as a dedicated API parameter. Effectiveness depends on the model following the instruction.
- **Max 4 restriction profiles** enforced only in the UI; the domain model has no hard limit.
- **Single error snackbar** is shared across all panes. Concurrent errors from multiple panes overwrite each other.
- **Temperature options are fixed** to `[0.0, 0.7, 1.0, 1.2]`; free-form input is not supported.
- **No retry logic.** Failed requests surface a snackbar and drop the response; the loading state is cleared.
- **`generatePromptFirst` in two-step mode** reads pane state between step 1 and step 2 from `_uiState.value` directly, which is a snapshot that could be stale if concurrent updates occur.
