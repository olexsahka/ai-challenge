# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## ═══ TOKEN EFFICIENCY RULES ═══

### Модель по задаче
- **Haiku** — простые правки (удалить строку, переименовать, добавить импорт, форматирование)
- **Sonnet** — реализация фич, дебаг, ревью, архитектурные решения (дефолт)
- Предлагать `/model haiku` перед простыми правками

### Параллельность
- Android (app/, shared/) и Backend (IdeaProjects/) — **всегда запускать параллельно** если нет явной зависимости
- Ревью android-review + backend-review — **всегда параллельно**
- Планировщики plan-android + plan-backend — **всегда параллельно**

### Diff-first подход
- При ревью/анализе — сначала `git diff master --name-only`, читать только изменённые файлы
- Читать другие файлы только если изменённый файл явно на них ссылается
- НЕ читать весь проект для понимания контекста — использовать grep по нужным символам

### Дебаг без участия пользователя
- Логи Android: `/debug-android` — не ждать пока пользователь вставит logcat
- Логи Backend: `/debug-backend` — не ждать пока пользователь вставит docker logs
- После backend rebuild: **автоматически** делать curl проверку tools/list и каждого нового tool
- При Docker: всегда проверять что образ пересобран (`docker compose build`), не использовать кэш

## ═══ ANTI-HALLUCINATION RULES — обязательны для ВСЕХ агентов ═══

### RULE-1: READ BEFORE ACT
Перед любым действием — прочитай нужные файлы через Read/Bash.
Никогда не полагайся на то что "помнишь" из предыдущих шагов.
Каждый шаг начинается с чтения актуального состояния файлов.

### RULE-2: VERIFY INPUTS EXIST
Перед стартом работы проверь что входные файлы существуют:
```bash
ls docs/specs/[feature].md docs/adr/[feature].md
```
Если файл не найден — СТОП. Сообщи: "❌ Не найден: [путь]. Нельзя продолжать."
Никогда не придумывай содержимое отсутствующего файла.

### RULE-3: QUOTE DON'T INVENT
При работе с требованиями — цитируй из документа, не перефразируй по памяти.
Формат: "Согласно docs/specs/[feature].md, раздел API: [цитата]"

### RULE-4: EXPLICIT UNKNOWNS
Если что-то неясно или отсутствует в документе — явно зафикируй:
"⚠️ НЕЯСНО: [что именно]. Дефолтное решение: [решение]. Требует подтверждения."
Никогда не угадывай молча.

### RULE-5: OUTPUT CHECKSUM
После завершения любого шага — выведи что именно было сделано:
```
✅ ВЫПОЛНЕНО:
  Создан: docs/specs/[feature].md
  Обновлён: docs/tasks/[feature].md
⚠️ НЕЯСНО (требует ответа):
  - [вопрос 1]
```

### RULE-6: ONE SESSION — ONE ROLE
Каждый агент выполняет ТОЛЬКО свою роль.
Если кажется что нужно сделать что-то из другой роли — СТОП.
Зафикируй задачу для нужного агента и завершай свой шаг.

### RULE-7: CONTEXT HYGIENE
Агент НЕ читает файлы которые не нужны для его текущей задачи.
Читай ТОЛЬКО: свой agent.md, нужные скилы, входные документы задачи.

## ═══ ФЛОУ РАЗРАБОТКИ (Documentation Driven Development) ═══

```
ДОКУМЕНТ создаётся → ВЕРИФИЦИРУЕТСЯ → КОД пишется по документу
Никакой код без: ADR + Spec + Tech Plan
```

### Команды (запускать в чистых сессиях)
```
/new-feature "name"    [Opus]   → ADR + Spec (аналитик)
/assign "name"         [Sonnet] → назначает планировщиков (менеджер)
/plan-backend "name"   [Opus]   → Backend Tech Plan
/plan-android "name"   [Opus]   → Android Tech Plan

backend-repo: bash .claude/hooks/validate-against-plan.sh → разработчик
android-repo: bash .claude/hooks/validate-against-plan.sh → android-shared → android-ui

/review-run "name"     [Opus]   → android-review + backend-review агенты → вердикт

REJECTED → /rework → validate-against-plan.sh → fix → /review-run
APPROVED → merge ✅
```

### Статусы задач
📋 BACKLOG → 🔍 ANALYSIS → 📄 ADR_READY → 📐 PLANNED
→ 🔨 IN_DEV → ✅ DEV_DONE → 🔍 IN_REVIEW → 🔄 REWORK_N → ✅ APPROVED

### Агенты и их расположение

| Агент | Где | Роль |
|-------|-----|------|
| analyst | ~/.claude/agents/ | глобальный, Opus — требования, ADR, Spec |
| manager | ~/.claude/agents/ | глобальный, Sonnet — assign, rework |
| android-planner | ~/.claude/agents/ | глобальный, Opus — Android Tech Plan |
| backend-planner | ~/.claude/agents/ | глобальный, Opus — Backend Tech Plan |
| orchestrator | .claude/agents/ | локальный — координация без кода |
| android-shared | .claude/agents/ | локальный — shared/ KMP |
| android-ui | .claude/agents/ | локальный — app/ UI/ViewModel/DI |
| backend | .claude/agents/ | локальный — Ktor/Spring Boot серверы |
| android-review | .claude/agents/ | локальный, read-only — ревью Android |
| backend-review | .claude/agents/ | локальный, read-only — ревью бэкенда |

### Хуки
```bash
bash .claude/hooks/validate-against-plan.sh [feature] [shared|ui|backend]
bash .claude/hooks/check-dod.sh [feature] [SH-01|UI-01|BE-01]
bash .claude/hooks/spec-coverage.sh [feature] [plan-android|plan-backend]
```

## Build & Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build and install on connected device
./gradlew installDebug

# Run all unit tests (175 тестов, 0 failures)
./gradlew :app:testDebugUnitTest

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean assembleDebug
```

## Architecture

Single-module Android app (`app/`) with MVVM + Clean Architecture layers:

```
presentation/  →  agent/  →  data/
```

### Key packages

| Package | Purpose |
|---|---|
| `presentation/agent/` | `AgentScreen`, `AgentViewModel`, `AgentUiState` — primary chat UI |
| `presentation/chat/` | `ChatScreen`, `ChatViewModel` — legacy/alternative chat UI |
| `agent/` | `LLMAgent` (core request logic), `AgentMemory` (SharedPreferences KV store), `AgentRunner` (ReAct loop agent), `TaskFsmRepository` (FSM state machine) |
| `data/api/` | `AnthropicApi` (Retrofit interface to OpenAI-compatible proxy) |
| `data/mcp/` | `McpClient` (OkHttp JSON-RPC + handshake for ВкусВилл), `McpRepository` (SharedPreferences toggle + proxy), `TelegramMcpClient` (stateless HTTP Basic Auth, no handshake), `TelegramMcpRepository`, `McpProviderFacade` (unified interface) |
| `data/db/` | Room database: DAOs + entities for sessions, messages, summaries, facts, branch nodes, task_fsm |
| `data/repository/UserProfileRepository.kt` | SharedPreferences store for User Profile (name, occupation, language, response style, format, notes) and Task Memory; `toContextString()` appends them to API instructions when enabled |
| `data/repository/ConstraintsRepository.kt` | SharedPreferences store for agent constraints (`rules: String`, `enabled: Boolean`); enforced on every FSM stage via pre- and post-checks |
| `domain/` | `Message`, `Settings` models; `ChatRepository` interface; `SendMessageUseCase` |
| `di/AppModule.kt` | Koin DI — all singletons and viewmodels wired here |

### Memory strategies (`MemoryStrategy` enum in `SessionEntity`)

Each session uses one of five strategies, selected per-session in context settings:

- `FULL` — send entire message history
- `SLIDING_WINDOW` — send last N messages (`slidingWindowN`)
- `STICKY_FACTS` — extract key facts via extra API call after each exchange; send facts + last N messages (`stickyFactsN`)
- `COMPRESSION` — summarize older messages when threshold crossed; send summary + last N (`compressionN`/`compressionM`)
- `BRANCHING` — tree-structured conversation via `BranchNodeEntity`; history built by walking ancestor chain to root

### Branching conversation

`BranchNodeEntity` forms a tree (parentId FK). `LLMAgent.buildBranchHistory()` walks from a node up to root, concatenating all ancestor messages in order. Messages are scoped per node via `branchNodeId`.

### `AgentRunner` (ReAct loop)

Standalone ReAct-style agent (max 6 iterations) used independently from `LLMAgent`. Tools: `SEARCH_MEMORY`, `STORE_MEMORY`, `CALCULATE`, `FINAL_ANSWER`, + dynamic MCP tools from any enabled provider. Parses `THOUGHT/ACTION/INPUT` lines from model output.

**Constructor:** `AgentRunner(api, memory, vararg mcpProviders: McpProviderFacade)` — accepts any number of MCP providers.

**Routing:** on each `run()`, builds `toolToProvider: Map<String, McpProviderFacade>` by calling `connect()` on every enabled provider. Tool calls are dispatched via this map — no if/else chain.

**Key behaviours:**
- If model responds without ReAct format (plain text) → entire response treated as `FINAL_ANSWER`
- MCP tools added to system prompt dynamically at start of each `run()` call via `connect()`
- MCP action input must be valid JSON; invalid JSON returns error OBSERVATION without calling `callTool`
- Model action names lowercased before routing — handles `SEND_MESSAGE` → `send_message`
- Messages saved via `agent.saveUserMessage(sessionId, text, nodeId)` and `saveAssistantMessage(...)` with `branchNodeId` to support all memory strategies

### MCP architecture

**`McpProviderFacade`** — unified interface implemented by both repositories:
```kotlin
interface McpProviderFacade {
    val isEnabled: Boolean
    val isConnected: Boolean
    suspend fun connect(): McpConnectionStatus
    fun disconnect()
    suspend fun callTool(toolName: String, arguments: JSONObject): String
}
```

**ВкусВилл (`McpClient` + `McpRepository`)** — server: `https://mcp001.vkusvill.ru/mcp`.
- Full handshake: `initialize` → `notifications/initialized` → `tools/list`
- Session ID returned in `Mcp-Session-Id` header, sent on all subsequent requests
- `McpRepository` is `open` — subclassable for testing (`FakeMcpRepository` pattern)

**Telegram (`TelegramMcpClient` + `TelegramMcpRepository`)** — local server: `http://10.0.2.2:8080/mcp` (emulator) / host `localhost:8080`.
- **No initialize handshake** — stateless HTTP POST, connects directly with `tools/list`
- **No session ID** — auth via HTTP Basic (`mcp` / `MCP_PASSWORD`)
- Source: [olexsahka/MCPTelegramServer](https://github.com/olexsahka/MCPTelegramServer)
- `extractResultText` handles two result shapes: (1) `result` is JSONArray → returns as string; (2) `result` is object with `content` array → returns `text` of first item
- `stripChatIds` (internal) — removes `chat_id`/`dialog_id` from response, populates `dialogIdMap` (title→id) as side-effect
- `resolveDialogId` (internal) — translates human-readable title to numeric Long id (exact, partial, "избранное" alias)
- Network: cleartext traffic for `10.0.2.2` permitted in `network_security_config.xml`

**Test dependency:** `org.json:json:20240303` added to `testImplementation` so unit tests can construct real `JSONObject` instances without Android runtime.

### Task FSM (`TaskFsmRepository` + `TaskFsmEntity`)

Multi-stage task execution engine. Stages: `PLANNING → EXECUTION (N steps) → VALIDATION → DONE`. Error state: `ERROR`.

**Modes:**
- **Manual (default):** Each stage requires user confirmation. After planning, agent asks "Proceed to step 1?". After each step asks for next step. User can reply or press "Run All" button.
- **Auto-run:** All remaining stages execute automatically. Triggered by "Run All" button or `sendMessageWithAutoRun`. Can be stopped mid-run (Stop button → `disableAutoRun`).

**Key fields in `TaskFsmEntity`:** `stage`, `step`, `stepCount`, `expectedAction`, `paused`, `autoRun`, `savedStage/Step/Action` (for pause/resume).

**Error handling:**
- If planning response has no numbered steps (bad input) → messages marked `isError=true`, FSM set to ERROR, user sees `❌` message.
- `isError=true` messages are excluded from `buildHistory` (not sent to API).
- On next `sendMessage` when FSM is ERROR → reset to PLANNING and restart.

**UI (FsmStatusBanner):** Shows current stage with color coding, step progress bar, АВТО badge when auto-running. Buttons: ▶ Run All / ⏸ Stop / ↺ Reset. Button "Запустить все этапы" appears above input field when task memory enabled and user has typed text.

### Agent Constraints (`ConstraintsRepository`)

Stored globally in SharedPreferences. When enabled, injected into FSM instructions and enforced on every stage:

- **Pre-check** (`checkConstraintViolation`) — before planning, user request is sent to LLM for constraint verification.
- **Post-check** (`checkResponseViolation`) — after each planning/execution response, the response text is verified.
- If violated → FSM transitions to `ERROR`, `handleConstraintViolation` saves an error message and makes a second API call to generate a concrete alternative request.
- Error message format (Russian): `❌ Ошибка: действие нарушает ограничение «...»` + suggested alternative.
- Constraints are separate from `UserInformation` — the `constraints` field was removed from `UserInformation` data class.

## Key Constraints

- **API key** хранится в `local.properties` как `PROXY_API_KEY`, передаётся через `BuildConfig.PROXY_API_KEY` в `di/AppModule.kt`. Backend — OpenAI-compatible proxy `https://api.proxyapi.ru/openai/v1/`.
- **MCP SDK:** `io.modelcontextprotocol:kotlin-sdk-client:0.9.0` + Ktor 3.2.3 added as dependencies (server artifacts excluded). Kotlin upgraded to 2.1.0, KSP 2.1.0-1.0.29 to match.
- **ВкусВилл MCP:** `https://mcp001.vkusvill.ru/mcp` — product search. Full initialize handshake + session ID header. `vkusVillEnabled` persisted in SharedPreferences (`mcp_prefs`).
- **Telegram MCP:** `http://10.0.2.2:8080/mcp` — local server (emulator). No initialize handshake, stateless HTTP POST, Basic Auth. Password from `local.properties` → `BuildConfig.TELEGRAM_MCP_PASSWORD`. `telegramEnabled` persisted in SharedPreferences (`telegram_mcp_prefs`).
- **`McpProviderFacade`** — interface implemented by both `McpRepository` and `TelegramMcpRepository`. `AgentRunner` takes `vararg McpProviderFacade` and routes tool calls via `toolToProvider` map built at `run()` start.
- **`McpRepository` is `open`** — allows `FakeMcpRepository` subclass in tests without Mockito suspend-function issues.
- **Room uses destructive migration** — schema changes wipe existing data.
- **`AgentMemory` (SharedPreferences) is global** — shared across all sessions.
- **`UserProfileRepository`** stores User Profile and Task Memory globally (SharedPreferences). When enabled via toggles, their content is appended to every request's instructions by `LLMAgent.buildInstructions()`. The `constraints` field was removed from `UserInformation` — use `ConstraintsRepository` instead.
- **`ConstraintsRepository`** stores agent constraints globally (SharedPreferences). When enabled, constraints are injected into FSM instructions via `TaskFsmRepository.toInstructionsBlock()`, and each user request + each stage response is verified against them via two extra synchronous API calls (`checkConstraintViolation` + `checkResponseViolation`), adding latency.
- `STICKY_FACTS` and `COMPRESSION` strategies make an extra API call synchronously within `sendMessage`, adding latency.
- Session title is auto-set from the first sentence of the first assistant response.
- **`jvmTarget = "11"`** — повышен с 1.8 для совместимости с mockito-kotlin тестами.
- **Kotlin 2.1.0** — upgraded from 1.9.25; uses `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` plugins.

## Testing

Unit тесты — 175 тестов, 0 failures.

```
app/src/test/java/com/example/myapplication/
├── AgentRunnerTest.kt           — ReAct loop (16 тестов)
├── AgentRunnerMcpTest.kt        — MCP интеграция AgentRunner (15 тестов)
├── BuildHistoryTest.kt          — 5 стратегий памяти (12 тестов)
├── BuildInstructionsTest.kt     — buildInstructions логика (11 тестов)
├── SendMessageTest.kt           — sendMessage full flow (11 тестов)
├── BuildBranchHistoryTest.kt    — branching history (9 тестов)
├── AgentMemoryTest.kt           — KV store (10 тестов)
├── UserProfileRepositoryTest.kt — profile/task context (10 тестов)
├── TaskFsmRepositoryTest.kt     — FSM state transitions, pause/resume, autoRun, error (24 тестов)
├── FsmLLMAgentTest.kt           — FSM интеграция в LLMAgent: ручной/авто режим, обработка ошибок (14 тестов)
├── ConstraintsRepositoryTest.kt — ConstraintsRepository: toContextBlock, defaults, enabled/disabled (8 тестов)
├── ConstraintsCheckTest.kt      — pre/post-check нарушений, альтернатива, toInstructionsBlock (13 тестов)
├── TelegramMcpClientTest.kt     — extractResultText (JSONArray/content/null), stripChatIds, resolveDialogId (15 тестов)
└── LLMAgentTestBase.kt          — Fake DAO инфраструктура (без тестов)
```

**Тестовая инфраструктура:** вместо реальных DAO используются `FakeSessionDao`, `FakeMessageDao`, `FakeSummaryDao`, `FakeFactDao`, `FakeBranchNodeDao` (in-memory, без Room/Android). `AgentMemory` и `UserProfileRepository` мокируются через Mockito (изолируют `Context`/`SharedPreferences`).

**Для тестов MCP:** `McpRepository` объявлен `open` — создавай `FakeMcpRepository : McpRepository(null, null)` и переопределяй `connect()`, `callTool()`, `isConnected`, `vkusVillEnabled`. Не используй Mockito для suspend-функций `McpRepository` — это ненадёжно без `coWhenever` (недоступен в mockito-kotlin 5.2.1). `TelegramMcpClientTest` использует методы `internal` напрямую (без reflection).

**Важно для будущих тестов:** `CapturingAnthropicApi.lastRequest` перезаписывается на каждый API вызов. Для стратегий с двумя вызовами (STICKY_FACTS, COMPRESSION) использовать `SequentialAnthropicApi.requests.first()` чтобы получить именно главный запрос.

## Coding Guidelines

Правила написания кода, выработанные в процессе рефакторинга проекта.

### Устранение дублирования

**Extension-функции для повторяющихся операций.**
Если одно и то же преобразование встречается в 2+ местах — выноси в extension.

```kotlin
// BAD — повторяется 5+ раз в проекте:
response.output.firstOrNull { it.type == "message" }
    ?.content?.firstOrNull { it.type == "output_text" }?.text

// GOOD — один extension в ChatResponse.kt:
fun ChatResponse.extractText(): String? =
    output.firstOrNull { it.type == "message" }
        ?.content?.firstOrNull { it.type == "output_text" }?.text
```

**Приватные helper-методы вместо копипасты внутри класса.**

```kotlin
// BAD — buildUserInfoLines() продублирован в двух методах
// GOOD — вынести в private fun buildUserInfoLines(info: UserInformation): List<String>
```

### Параметры функций

**Больше 4 параметров → data class.**

```kotlin
// BAD:
fun updateSessionContext(
    sessionId: String, systemPrompt: String, model: String,
    temperature: Float, compressionEnabled: Boolean,
    compressionN: Int, compressionM: Int,
    memoryStrategy: String, slidingWindowN: Int, stickyFactsN: Int
)

// GOOD:
data class SessionContextConfig(
    val systemPrompt: String,
    val model: String,
    val temperature: Float,
    val compressionEnabled: Boolean,
    val compressionN: Int,
    val compressionM: Int,
    val memoryStrategy: String,
    val slidingWindowN: Int,
    val stickyFactsN: Int
)
fun updateSessionContext(sessionId: String, config: SessionContextConfig)
```

Это же правило распространяется на лямбды `onSave` в Composable — передавай data class, не 9 отдельных параметров.

### Управление Job / корутинами в ViewModel

**Группируй связанные Job в Map, не в отдельные поля.**

```kotlin
// BAD:
private var summaryJob: Job? = null
private var factsJob: Job? = null
private var branchJob: Job? = null
private var fsmJob: Job? = null
// ...
summaryJob?.cancel(); summaryJob = viewModelScope.launch { ... }
factsJob?.cancel();   factsJob   = viewModelScope.launch { ... }

// GOOD:
private val sessionJobs = mutableMapOf<String, Job>()
// ...
sessionJobs.values.forEach { it.cancel() }
sessionJobs.clear()
sessionJobs["summary"] = viewModelScope.launch { ... }
sessionJobs["facts"]   = viewModelScope.launch { ... }
```

### Дублирующая логика с вариацией

**Выноси общий каркас, параметризуй различия.**

```kotlin
// BAD — checkConstraintViolation и checkResponseViolation имели
//        идентичный блок «отправь запрос → распарси VIOLATION:»

// GOOD — общий private helper:
private suspend fun askConstraintsChecker(session: SessionEntity, prompt: String): String?

// Каждый публичный метод только формирует свой prompt и делегирует:
private suspend fun checkConstraintViolation(...): String? {
    val prompt = "...user request prompt..."
    return askConstraintsChecker(session, prompt)
}
private suspend fun checkResponseViolation(...): String? {
    val prompt = "...response check prompt..."
    return askConstraintsChecker(session, prompt)
}
```

### Composable-функции

**Лямбды с >4 параметрами заменяй data class.**

```kotlin
// BAD:
onSave: (String, String, Float, Boolean, Int, Int, String, Int, Int) -> Unit

// GOOD:
onSave: (SessionContextConfig) -> Unit
```

### Общий принцип

- Одна операция — одно место в коде. Если меняешь логику, меняешь в одном файле.
- Не выноси в отдельный класс то, что используется только в одном месте.
- Не создавай абстракции "на будущее" — только под реальную потребность.

## KMP Migration

Проект в процессе подготовки к Kotlin Multiplatform. План: [`KMP_MIGRATION_PLAN.md`](KMP_MIGRATION_PLAN.md).

**Текущий статус:** Фаза 0 завершена (175 тестов). Kotlin обновлён до 2.1.0, MCP SDK добавлен, `McpProviderFacade` введён. Следующий шаг — Фаза 1 (выделение domain слоя, введение интерфейсов репозиториев).
