# MyApplication

## 1. Project Overview

**Purpose:** Android AI agent application with persistent sessions, memory, and per-session context configuration.

**Core idea:** A single screen hosts a session-based LLM chat backed by `LLMAgent`. Each session stores its full message history in a local Room database and is restored on app restart. The agent supports a configurable system prompt, model, and temperature per session. A shared key-value memory store (SharedPreferences) is automatically injected into every request so the model has cross-session context. Sessions can optionally enable memory compression, which sends only the last N messages plus a persisted summary of older messages.

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
| `AgentScreen` | Session sidebar, message list, summary pin banner, message input, context settings bottom sheet |
| `AgentViewModel` | Owns `AgentUiState`; bridges UI to `LLMAgent`; exposes session list, active messages, active summary, and memory entries as `StateFlow` |
| `LLMAgent` | Encapsulates all request logic: builds history from DB, assembles instructions (system prompt + memory), applies compression if enabled, calls API, persists both user and assistant messages and summaries |
| `AgentMemory` | Key-value memory store backed by `SharedPreferences`; injected into every request as additional instructions |
| `UserProfileRepository` | Stores User Profile (name, occupation, language, response style, format, notes) + toggle and Task Memory (name, description) + toggle in `SharedPreferences`; appended to every request's instructions when the respective toggle is enabled |
| `ConstraintsRepository` | Stores agent constraints (rules text + enabled toggle) in `SharedPreferences`; enforced on every FSM stage via pre- and post-checks against the LLM |
| `AppDatabase` | Room database with `sessions`, `messages`, and `summaries` tables |
| `SessionContextConfig` | Data class grouping all 9 per-session context settings; passed to `updateSessionContext` and `saveSessionContext` instead of individual parameters |
| `SessionDao` | CRUD for sessions; `observeAll()`, `getLatest()`, `getById()`, `updateContext()` |
| `MessageDao` | Insert and observe messages by session |
| `SummaryDao` | Upsert and observe compression summaries by session |
| `AnthropicApi` | Retrofit interface; `POST /responses` and `GET /models` endpoints |
| `AgentRunner` | Standalone ReAct-loop agent (max 6 iterations); integrates MCP tools from any enabled `McpProviderFacade`; routes tool calls via `toolToProvider` map; executed via `AgentViewModel.runAgentWithMcp` |
| `McpProviderFacade` | Unified interface for all MCP providers (`isEnabled`, `connect()`, `callTool()`, `disconnect()`); implemented by `McpRepository` and `TelegramMcpRepository` |
| `McpClient` | Raw HTTP client for ВкусВилл MCP (JSON-RPC); handles full `initialize → notifications/initialized → tools/list → tools/call` handshake with `Mcp-Session-Id` |
| `McpRepository` | Thin wrapper around `McpClient`; persists `vkusVillEnabled` in SharedPreferences; implements `McpProviderFacade` |
| `TelegramMcpClient` | Stateless HTTP Basic Auth MCP client for local Telegram server (`http://10.0.2.2:8080/mcp`); no initialize handshake; `dialog_id` resolution via internal `dialogIdMap` |
| `TelegramMcpRepository` | Thin wrapper around `TelegramMcpClient`; persists `telegramEnabled`; implements `McpProviderFacade` |

### Component interactions

```
User input
    └─► AgentViewModel.sendMessage()
            └─► LLMAgent.sendMessage(sessionId, text)
                    ├─► messageDao.insert(userMessage)
                    ├─► sessionDao.getById()        → loads model, temperature, systemPrompt, compression settings
                    ├─► buildHistory()              → full history OR compressed history (summary + last N)
                    │       └─► summaryDao.getBySession()   → load persisted summary
                    │       └─► api.sendMessage()           → generate new summary if needed
                    │       └─► summaryDao.upsert()         → persist updated summary
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
                    └─► sessionDao.updateContext()  (systemPrompt, model, temperature, compression settings)

Active session switch
    └─► AgentViewModel.activateSession()
            └─► LLMAgent.observeSummary(sessionId)  → live summary via Flow → uiState.activeSummary
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
- `compressionEnabled` — whether memory compression is active for this session
- `compressionN` — number of recent messages to send in full (default 5)
- `compressionM` — how many new older messages must accumulate before the summary is regenerated (default 6)

On every app start the most recent session is restored automatically. The session sidebar lists all sessions ordered newest-first; tapping one switches the active session and loads its message history live from the DB.

### Message persistence

Every user message is written to the DB **before** the API call. The assistant response is written after a successful response. Both are stored in `MessageEntity` with the session FK, timestamp, and — for assistant messages — token counts, latency, and model.

### Response parsing (`extractText`)

`ChatResponse.extractText()` — extension-функция в `ChatResponse.kt`. Единственное место в коде, где извлекается текст из API-ответа:

```kotlin
fun ChatResponse.extractText(): String? =
    output.firstOrNull { it.type == "message" }
        ?.content?.firstOrNull { it.type == "output_text" }?.text
```

Используется везде: `LLMAgent`, `ChatRepositoryImpl`, `AgentRunner`. Не дублировать эту цепочку inline.

### Instructions assembly (`buildInstructions`)

Called inside `LLMAgent.sendMessage()` before every request:

1. If the session has a non-blank `systemPrompt`, it is the first block.
2. `AgentMemory.toContextString()` appends all stored key-value memories as a bullet list under "Stored memories:".
3. The two blocks are joined with a blank line. If both are empty, `null` is passed and no `instructions` field is sent.

### Memory compression (`buildHistory`)

When `compressionEnabled` is `false` or the total message count is ≤ N, the full history is sent as-is.

When enabled and history exceeds N messages:
1. Messages are split into `older` (all except last N) and `recent` (last N).
2. `getOrUpdateSummary` checks the `summaries` table for a persisted `SummaryEntity`:
   - If one exists and `olderCount - coveredMessageCount < M`, the cached summary is reused.
   - Otherwise a dedicated summarization API call is made, and the result is persisted via `summaryDao.upsert()`.
3. The final input sent to the API is: a synthetic user/assistant pair injecting the summary, followed by the N recent messages.

### Summary persistence (`SummaryEntity`)

Stored in the `summaries` table with:
- `sessionId` — PK and FK to `sessions` (CASCADE delete)
- `summary` — the full summary text
- `coveredMessageCount` — how many older messages were included when this summary was generated
- `updatedAt` — timestamp of last update

### Summary pin banner

When compression is enabled and a summary exists for the active session, a pinned banner appears above the message list showing the first sentence of the summary. Tapping it opens a dialog with the full summary text. The banner updates reactively via `observeSummary` → `StateFlow`.

### MCP Integration

When any MCP toggle is enabled, messages are routed through `AgentRunner` instead of `LLMAgent.sendMessage`. Multiple providers can be active simultaneously.

**ВкусВилл (product search, `https://mcp001.vkusvill.ru/mcp`):**
1. Full handshake: `initialize` → receives `Mcp-Session-Id` → `notifications/initialized` → `tools/list`
2. Session ID sent on all subsequent requests

**Telegram (local server, `http://10.0.2.2:8080/mcp`):**
1. No initialize handshake — stateless HTTP POST with HTTP Basic Auth
2. Directly calls `tools/list`, then `tools/call`
3. Tools: `get_dialogs`, `search_dialog`, `get_unread_messages`, `get_last_messages`, `send_message`
4. `dialog_id` accepts Long; `TelegramMcpClient` resolves string titles to numeric IDs automatically via `dialogIdMap`

**Per-message flow:**
1. `AgentRunner.run()` calls `connect()` on each enabled provider → builds `toolToProvider: Map<String, McpProviderFacade>`
2. `buildSystemPrompt(tools)` injects all tool names and field descriptions into the system prompt
3. The ReAct loop parses `THOUGHT / ACTION / INPUT` from model response
4. Action name lowercased → looked up in `toolToProvider` → dispatched to the owning provider
5. Tool result appended as `OBSERVATION`, loop continues
6. Plain text response (no ReAct format) → treated as `FINAL_ANSWER`
7. On `FINAL_ANSWER`, saved via `agent.saveAssistantMessage(sessionId, content, nodeId)`

**UI:**
- Context settings bottom sheet → "MCP Servers" section with ВкусВилл and Telegram toggles
- Connection status per server: Подключение... / Подключён · N инструментов / Ошибка: ...
- Expandable tool list under each toggle when connected
- Clickable links in assistant messages open the browser

### Memory store

`AgentMemory` wraps `SharedPreferences` as a flat key-value store shared across all sessions. Entries are displayed and managed in the context settings bottom sheet. Individual keys can be deleted; "Clear all" is available with a confirmation dialog.

### User Profile

Stored globally in `UserProfileRepository` (SharedPreferences). The context settings bottom sheet exposes a "User Profile" section with fields: name, occupation, language, response style, response format, additional notes.
- **User Data Usage** toggle — when enabled, the profile fields are appended to every request's instructions under "User information:".

### Task Memory

Also stored globally in `UserProfileRepository`. The context settings bottom sheet exposes a "Task Memory" section with:
- **Task name** — short label for the current task.
- **Task description** — detailed description of the task.
- **Task Memory Usage** toggle — when enabled, task name and description are appended to every request's instructions under "Current task:".

### Agent Constraints

Stored globally in `ConstraintsRepository` (SharedPreferences), separate from the conversation and user profile. The context settings bottom sheet exposes an "Agent Constraints" section with:
- **Rules** — free-form text, one rule per line (e.g. "no code generation", "reply in English only").
- **Enable constraints** toggle — when enabled, constraints are enforced on every FSM stage.

**How enforcement works:**
1. **Pre-check** — before planning begins, `LLMAgent` sends the user's request to the LLM asking whether it violates any rule.
2. **Post-check** — after each FSM stage response (planning, each execution step), the response text is checked the same way.
3. If a violation is detected at any point:
   - The FSM transitions to `ERROR`.
   - The agent saves an error message with the violated constraint name.
   - A second API call generates a concrete alternative request that achieves a similar goal without violating the rules.
   - The user sees the violation description, two options (update constraints or use the alternative), and the suggested alternative text.
4. After the user updates constraints or rephrases their request, the next `sendMessage` resets the FSM and starts fresh planning.

**Key class:** `ConstraintsRepository` — stores `rules: String` and `enabled: Boolean` in SharedPreferences; `toContextBlock()` generates the constraints section injected into FSM instructions via `TaskFsmRepository.toInstructionsBlock()`.

### Task FSM (Finite State Machine)

When Task Memory is enabled, `LLMAgent` drives execution through a structured FSM stored in `TaskFsmEntity`:

```
PLANNING → EXECUTION (step 1..N) → VALIDATION → DONE
                                              ↘ ERROR (on bad input)
```

**Manual mode (default):** Each stage requires explicit user confirmation. After each API call, a prompt message is saved asking the user to proceed to the next step.

**Auto-run mode:** Triggered via the "Run All" button (▶) in the FSM banner or `sendMessageAutoRun`. All stages execute automatically in sequence. The Stop button (⏸) sets `autoRun=false`; the loop checks this flag between API calls and halts at the current step.

**Error handling:** If the planning response contains no numbered steps, the FSM transitions to `ERROR` stage, the bad messages are marked `isError=true` (excluded from future API history but still visible in UI), and the user is prompted to retry. On the next `sendMessage`, ERROR stage triggers a full FSM reset and fresh planning.

**Key classes:**
- `TaskFsmEntity` — Room entity holding `stage`, `step`, `stepCount`, `autoRun`, `paused`, and saved-state fields for pause/resume
- `TaskFsmRepository` — state transition logic: `transitionTo`, `markDone`, `setError`, `pause/resume`, `enableAutoRun/disableAutoRun`, `reset`
- `LLMAgent.runFsmStep()` — executes one FSM stage per call; loops automatically only when `autoRun=true`

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
| Memory Compression | Toggle (enabled / disabled) |
| Send last n messages | Integer, default 5 (visible when compression enabled) |
| Update summary every m messages | Integer, default 6 (visible when compression enabled) |
| Agent Constraints | Toggle + free-form rules text (one per line); enforced on every FSM stage |

---

## 4. KMP Migration

Проект готовится к миграции на Kotlin Multiplatform. Подробный план — в [`KMP_MIGRATION_PLAN.md`](KMP_MIGRATION_PLAN.md).

| Фаза | Статус |
|---|---|
| Фаза 0 — Baseline тесты + MCP рефакторинг | ✅ Завершена (175 тестов; Kotlin 2.1.0; McpProviderFacade) |
| Фаза 1 — Domain слой | 🔲 Не начата |
| Фаза 2 — Platform абстракции | 🔲 Не начата |
| Фаза 3 — Shared KMP модуль | 🔲 Не начата |
| Фаза 4 — Ktor / serialization | 🔲 Не начата |
| Фаза 5 — JS таргет / Web клиент | 🔲 Не начата |

### Тестовое покрытие (baseline)

```
app/src/test/
├── AgentRunnerTest.kt           — ReAct loop, все действия, maxIterations (16 тестов)
├── AgentRunnerMcpTest.kt        — MCP интеграция: plain-text fallback, tool execution, JSON args, prompt (15 тестов)
├── BuildHistoryTest.kt          — все 5 стратегий памяти (12 тестов)
├── BuildInstructionsTest.kt     — сборка системного промпта (11 тестов)
├── SendMessageTest.kt           — sendMessage full flow + persistence (11 тестов)
├── BuildBranchHistoryTest.kt    — branching history по ancestor chain (9 тестов)
├── AgentMemoryTest.kt           — KV store логика (10 тестов)
├── UserProfileRepositoryTest.kt — profile/task context строки (10 тестов)
├── TaskFsmRepositoryTest.kt     — FSM state transitions, pause/resume, autoRun, error (24 тестов)
├── FsmLLMAgentTest.kt           — FSM интеграция в LLMAgent: ручной/авто режим, обработка ошибок (14 тестов)
├── ConstraintsRepositoryTest.kt — ConstraintsRepository: toContextBlock, defaults, enabled/disabled (8 тестов)
├── ConstraintsCheckTest.kt      — pre/post-check нарушений, альтернатива, toInstructionsBlock (13 тестов)
├── TelegramMcpClientTest.kt     — extractResultText (JSONArray/content/null), stripChatIds, resolveDialogId (15 тестов)
└── LLMAgentTestBase.kt          — Fake DAO инфраструктура (без тестов)
```

---

## 5. Tech Stack

| Category | Technology |
|---|---|
| Language | Kotlin 2.1.0 |
| UI | Jetpack Compose (Material 3) |
| Architecture | MVVM + Clean Architecture (domain / data / presentation) |
| Dependency injection | Koin |
| Networking | Retrofit 2 + OkHttp 3 (main API); Ktor 3.2.3 (MCP SDK) |
| JSON serialization | Gson (API) + `org.json` (MCP) |
| Async | Kotlin Coroutines + `StateFlow` |
| Local persistence | Room (sessions + messages + summaries) |
| Memory store | `SharedPreferences` |
| API backend | OpenAI-compatible proxy (`api.proxyapi.ru`) |
| MCP — ВкусВилл | JSON-RPC + full handshake (`mcp001.vkusvill.ru/mcp`); OkHttp |
| MCP — Telegram | Stateless JSON-RPC, Basic Auth (`10.0.2.2:8080/mcp`); OkHttp |
| MCP SDK | `io.modelcontextprotocol:kotlin-sdk-client:0.9.0` (добавлен, готов к использованию) |

---

## 6. Limitations & Assumptions

- **API key is hardcoded** in `AppModule.kt`. There is no secure storage or runtime configuration.
- **Memory is global**, not per-session. All sessions share the same `AgentMemory` store.
- **User Profile and Task Memory are global**, not per-session. All sessions share the same `UserProfileRepository` store.
- **No migration strategy beyond destructive.** The Room database uses `fallbackToDestructiveMigration()`; schema changes wipe existing data.
- **Temperature options are fixed** to `[0.0, 0.7, 1.0, 1.2]`; free-form input is not supported.
- **Model list is hardcoded** in the UI (`AVAILABLE_MODELS`); it is not fetched live from the API.
- **No retry logic.** Failed requests surface a snackbar and drop the loading state; the user message remains in the DB.
- **Single error snackbar** — only the most recent error is shown.
- **`buildHistory` uses `.first()`** on the Flow, which reads the DB state at the moment of the call. Under very high concurrency this could miss a just-inserted message, but in practice the user message is inserted synchronously before `buildHistory` is called.
- **Summary regeneration is eager** — when the M threshold is crossed, the summary API call is made synchronously as part of `sendMessage`, adding latency to that request.
- **Compression N and M are integers only** — free-form float or negative values are rejected in the UI (digit-only filter, coerced to minimum 1 on save).

---

## Claude Code Team — флоу разработки

Проект использует **Documentation Driven Development**: код пишется только после создания ADR + Spec + Tech Plan.

### Агенты

#### Глобальные (`~/.claude/agents/`) — доступны во всех проектах
| Агент | Модель | Роль |
|-------|--------|------|
| `analyst` | Opus | Собирает требования, создаёт ADR + Spec |
| `manager` | Sonnet | Назначает планировщиков, обрабатывает rework |
| `android-planner` | Opus | Создаёт Android Tech Plan (SH-* + UI-* задачи) |
| `backend-planner` | Opus | Создаёт Backend Tech Plan (BE-* задачи) |

> `android-planner` и `backend-planner` при старте сообщают текущую директорию и ждут подтверждения — они глобальные и могут запуститься не в том проекте.

#### Локальные (`.claude/agents/`) — только этот проект
| Агент | Права | Зона |
|-------|-------|------|
| `orchestrator` | Read, Glob, Grep, Agent | Координация, не пишет код |
| `android-shared` | Read, Edit, Write, Glob, Grep, Bash | `shared/` KMP модуль |
| `android-ui` | Read, Edit, Write, Glob, Grep, Bash | `app/` UI, ViewModel, Koin, Room |
| `backend` | Read, Edit, Write, Glob, Grep, Bash | Внешние бэкенд-проекты |
| `web` | Read, Edit, Write, Glob, Grep, Bash | `webClient/` Kotlin/JS |
| `android-review` | Read, Glob, Grep, Bash | Ревью Android — только читает |
| `backend-review` | Read, Glob, Grep, Bash | Ревью бэкенда — только читает |

---

### Флоу для новой фичи

> **Каждый шаг = новая сессия.** Это защита от деградации контекста.

#### Шаг 1 — Требования и документация
```
/new-feature "название-фичи"
```
Аналитик (Opus) задаёт вопросы, создаёт:
- `docs/adr/название-фичи.md`
- `docs/specs/название-фичи.md`
- `docs/tasks/название-фичи.md`

Закрыть сессию после OUTPUT CHECKSUM.

#### Шаг 2 — Назначение планировщиков
```
/assign "название-фичи"
```
Менеджер читает документы и определяет: нужен ли `/plan-backend`, `/plan-android`, или оба и в каком порядке.

#### Шаг 3 — Tech Plans
Если есть бэкенд (новая сессия):
```
/plan-backend "название-фичи"
```
Создаёт `docs/plans/название-фичи-backend.md` с задачами BE-01..N, API контрактом, сигнатурами.

Потом (новая сессия):
```
/plan-android "название-фичи"
```
Создаёт `docs/plans/название-фичи-android.md` с задачами SH-01..N и UI-01..N.

#### Шаг 4 — Разработка

**Бэкенд** (сессия в папке бэкенд-проекта):
```bash
bash .claude/hooks/validate-against-plan.sh название-фичи backend
# → агент backend пишет код строго по выводу
bash .claude/hooks/check-dod.sh название-фичи BE-01
```

**Android shared** (сессия в MyApplication):
```bash
bash .claude/hooks/validate-against-plan.sh название-фичи shared
# → агент android-shared пишет domain/interfaces в shared/
bash .claude/hooks/check-dod.sh название-фичи SH-01
```

**Android UI** (после SH-* готовы):
```bash
bash .claude/hooks/validate-against-plan.sh название-фичи ui
# → агент android-ui пишет Compose/ViewModel/Koin в app/
bash .claude/hooks/check-dod.sh название-фичи UI-01
```

> Разработчик **не пишет код "из головы"** — только по выводу `validate-against-plan.sh`.
> Хочешь добавить что-то чего нет в выводе → стоп → вопрос менеджеру.

#### Шаг 5 — Ревью
```
/review-run "название-фичи"
```
Запускаются `android-review` + `backend-review` параллельно. Создаётся `docs/reviews/название-фичи-review-1.md`.

- **APPROVED** → merge ✅
- **REJECTED** → шаг 6

#### Шаг 6 — Доработка (если REJECTED)
```
/rework "название-фичи"
```
Менеджер читает review, обновляет планы. Разработчики снова запускают `validate-against-plan.sh`, исправляют, затем снова `/review-run`.

---

### Статусы задач
```
📋 BACKLOG → 🔍 ANALYSIS → 📄 ADR_READY → 📐 PLANNED
→ 🔨 IN_DEV → ✅ DEV_DONE → 🔍 IN_REVIEW → 🔄 REWORK_N → ✅ APPROVED
```

Текущий статус всех фич:
```
/status
```

### Хуки
```bash
# Показывает задачи и сигнатуры из плана — запускать перед разработкой
bash .claude/hooks/validate-against-plan.sh <feature> <shared|ui|backend>

# Проверяет DoD задачи — запускать после каждой задачи
bash .claude/hooks/check-dod.sh <feature> <SH-01|UI-01|BE-01>

# Проверяет покрытие AC из Spec в плане
bash .claude/hooks/spec-coverage.sh <feature> <plan-android|plan-backend>
```

### Документация
```
docs/
├── adr/          — Architecture Decision Records
├── specs/        — Feature Specifications
├── plans/        — Tech Plans (backend + android)
├── tasks/        — Статусы задач
└── reviews/      — Review reports
```