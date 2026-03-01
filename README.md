# MyApplication

## 1. Project Overview

**Purpose:** Android AI agent application with persistent sessions, memory, and per-session context configuration.

**Core idea:** A single screen hosts a session-based LLM chat backed by `LLMAgent`. Each session stores its full message history in a local Room database and is restored on app restart. The agent supports a configurable system prompt, model, and temperature per session. A shared key-value memory store (SharedPreferences) is automatically injected into every request so the model has cross-session context.

---

## 2. Architecture

### High-level layers

```
Presentation  →  Domain  →  Data
     │                         │
AgentScreen              AnthropicApi (Retrofit)
AgentViewModel           LLMAgent
                         AppDatabase (Room)
                         AgentMemory (SharedPreferences)
```

### Component responsibilities

| Component | Responsibility |
|---|---|
| `MainActivity` | Single-activity host; renders `AgentScreen` |
| `MyApp` | Application class; initializes Koin DI |
| `AgentScreen` | Session sidebar, message list, message input, context settings bottom sheet |
| `AgentViewModel` | Owns `AgentUiState`; bridges UI to `LLMAgent`; exposes session list, active messages, and memory entries as `StateFlow` |
| `LLMAgent` | Encapsulates all request logic: builds history from DB, assembles instructions (system prompt + memory), calls API, persists both user and assistant messages |
| `AgentMemory` | Key-value memory store backed by `SharedPreferences`; injected into every request as additional instructions |
| `AppDatabase` | Room database with `sessions` and `messages` tables |
| `SessionDao` | CRUD for sessions; `observeAll()`, `getLatest()`, `getById()`, `updateContext()` |
| `MessageDao` | Insert and observe messages by session |
| `AnthropicApi` | Retrofit interface; `POST /responses` and `GET /models` endpoints |

### Component interactions

```
User input
    └─► AgentViewModel.sendMessage()
            └─► LLMAgent.sendMessage(sessionId, text)
                    ├─► messageDao.insert(userMessage)
                    ├─► sessionDao.getById()        → loads model, temperature, systemPrompt
                    ├─► messageDao.observeBySession().first()  → full conversation history
                    ├─► AgentMemory.toContextString()  → appended to instructions
                    └─► AnthropicApi.sendMessage()
                            └─► POST https://api.proxyapi.ru/openai/v1/responses
                    └─► messageDao.insert(assistantMessage)
```

Session lifecycle:
```
App start
    └─► AgentViewModel.init
            └─► LLMAgent.getOrRestoreLastSession()  → restores most recent session
            └─► LLMAgent.observeSessions()           → live session list via Flow

New session
    └─► AgentViewModel.newSession()
            └─► LLMAgent.createSession()  → inserts SessionEntity with timestamp title

Context settings save
    └─► AgentViewModel.saveSessionContext()
            └─► LLMAgent.updateSessionContext()
                    └─► sessionDao.updateContext()  (systemPrompt, model, temperature)
```

---

## 3. Core Logic

### Session management

Each session is a `SessionEntity` row with:
- `id` — UUID primary key
- `startedAt` — Unix timestamp in ms
- `title` — formatted start time ("dd MMM yyyy, HH:mm")
- `systemPrompt` — injected as the first block of API instructions
- `model` — model ID used for all messages in the session
- `temperature` — passed to the API (omitted if 1.0)

On every app start the most recent session is restored automatically. The session sidebar lists all sessions ordered newest-first; tapping one switches the active session and loads its message history live from the DB.

### Message persistence

Every user message is written to the DB **before** the API call. The assistant response is written after a successful response. Both are stored in `MessageEntity` with the session FK, timestamp, and — for assistant messages — token counts, latency, and model.

### Instructions assembly (`buildInstructions`)

Called inside `LLMAgent.sendMessage()` before every request:

1. If the session has a non-blank `systemPrompt`, it is the first block.
2. `AgentMemory.toContextString()` appends all stored key-value memories as a bullet list under "Stored memories:".
3. The two blocks are joined with a blank line. If both are empty, `null` is passed and no `instructions` field is sent.

### Memory store

`AgentMemory` wraps `SharedPreferences` as a flat key-value store shared across all sessions. Entries are displayed and managed in the context settings bottom sheet. Individual keys can be deleted; "Clear all" is available with a confirmation dialog.

### Response metadata (`MessageMeta`)

Every assistant message carries:

| Field | Source |
|---|---|
| `inputTokens` | `usage.input_tokens` from API response |
| `outputTokens` | `usage.output_tokens` from API response |
| `durationMs` | Wall-clock time around the API call |
| `model` | Model ID from the session at send time |

Displayed below each assistant bubble as:
```
↑<input> ↓<output> · <total>tok · <duration> · <model>
```

### Context settings (per session)

Accessible via the gear icon in the top bar. Stored in the `sessions` table:

| Setting | Options |
|---|---|
| System prompt | Free-form text, multi-line |
| Model | gpt-4o-mini, gpt-4o, gpt-4-turbo, gpt-3.5-turbo, o1-mini, o3-mini |
| Temperature | 0.0, 0.7, 1.0, 1.2 |

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
| Local persistence | Room (sessions + messages) |
| Memory store | `SharedPreferences` |
| API backend | OpenAI-compatible proxy (`api.proxyapi.ru`) |

---

## 5. Limitations & Assumptions

- **API key is hardcoded** in `AppModule.kt`. There is no secure storage or runtime configuration.
- **Memory is global**, not per-session. All sessions share the same `AgentMemory` store.
- **No migration strategy beyond destructive.** The Room database uses `fallbackToDestructiveMigration()`; schema changes wipe existing data.
- **Temperature options are fixed** to `[0.0, 0.7, 1.0, 1.2]`; free-form input is not supported.
- **Model list is hardcoded** in the UI (`AVAILABLE_MODELS`); it is not fetched live from the API.
- **No retry logic.** Failed requests surface a snackbar and drop the loading state; the user message remains in the DB.
- **Single error snackbar** — only the most recent error is shown.
- **`buildHistory` uses `.first()`** on the Flow, which reads the DB state at the moment of the call. Under very high concurrency this could miss a just-inserted message, but in practice the user message is inserted synchronously before `buildHistory` is called.
