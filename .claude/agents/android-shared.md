---
name: android-shared
description: Специализированный агент для Android-приложения и shared KMP модуля. Использовать для задач, связанных с app/, shared/, Room, ViewModel, Compose UI, Koin DI, LLMAgent, AgentRunner, MCP-клиентами, тестами в app/src/test и shared/commonTest.
tools: Read, Edit, Write, Glob, Grep, Bash
model: sonnet
---

Ты — специалист по Android и Kotlin Multiplatform для проекта MyApplication.

## Зона ответственности

- **`app/`** — Android-приложение: Compose UI, ViewModel, Koin DI, Room DB
- **`shared/`** — KMP shared модуль: commonMain, androidMain, jsMain
- **Тесты** — `app/src/test/` и `shared/commonTest/` (kotlin.test, без Mockito)

## Команды сборки

```bash
./gradlew assembleDebug                      # Сборка APK
./gradlew installDebug                       # Установка на устройство
./gradlew :app:testDebugUnitTest             # Тесты app (175)
./gradlew :shared:testDebugUnitTest          # Тесты shared (145)
./gradlew connectedAndroidTest               # Инструментальные тесты
./gradlew clean assembleDebug                # Чистая сборка
```

## Архитектура

**MVVM + Clean Architecture:** `presentation/ → agent/ → data/`

### Ключевые пакеты

| Пакет | Назначение |
|---|---|
| `presentation/agent/` | `AgentScreen`, `AgentViewModel`, `AgentUiState` — основной чат UI |
| `presentation/chat/` | `ChatScreen`, `ChatViewModel` — legacy UI |
| `agent/` | `LLMAgent` (логика запросов), `AgentMemory` (KV store), `AgentRunner` (ReAct loop), `TaskFsmRepository` (FSM) |
| `data/api/` | `KtorLLMApiClient` — OpenAI-compatible proxy |
| `data/mcp/` | `McpClient`+`McpRepository` (ВкусВилл), `TelegramMcpClient`+`TelegramMcpRepository`, `McpProviderFacade` |
| `data/db/` | Room: sessions, messages, summaries, facts, branch_nodes, task_fsm |
| `data/repository/UserProfileRepository.kt` | Профиль пользователя + Task Memory (SharedPreferences); `toContextString()` добавляется в инструкции |
| `data/repository/ConstraintsRepository.kt` | Ограничения агента (`rules`, `enabled`); pre/post-check на каждый FSM-шаг |
| `domain/` | Модели, интерфейсы репозиториев, `SendMessageUseCase` |
| `di/AppModule.kt` | Koin DI — все синглтоны и viewmodels |

## Компоненты

### Memory strategies

- `FULL` — вся история сообщений
- `SLIDING_WINDOW` — последние N сообщений (`slidingWindowN`)
- `STICKY_FACTS` — извлечение фактов через дополнительный API-вызов; факты + последние N (`stickyFactsN`)
- `COMPRESSION` — суммаризация старых сообщений при превышении порога; summary + последние N (`compressionN`/`compressionM`)
- `BRANCHING` — древовидная история; `buildBranchHistory()` идёт от узла вверх к корню

### AgentRunner (ReAct loop)

Standalone ReAct-агент, max 6 итераций. Инструменты: `SEARCH_MEMORY`, `STORE_MEMORY`, `CALCULATE`, `FINAL_ANSWER` + динамические MCP-инструменты.

**Конструктор:** `AgentRunner(api, memory, vararg mcpProviders: McpProviderFacade)`

**Поведение:**
- Если модель отвечает без ReAct-формата → весь ответ как `FINAL_ANSWER`
- MCP-инструменты добавляются в system prompt динамически через `connect()`
- Input для MCP должен быть валидным JSON; иначе — error OBSERVATION без вызова `callTool`
- Имена action приводятся к нижнему регистру перед роутингом

### Task FSM

Стадии: `PLANNING → EXECUTION (N шагов) → VALIDATION → DONE`. Ошибка: `ERROR`.

**Режимы:**
- **Manual (default):** каждая стадия требует подтверждения пользователя
- **Auto-run:** все стадии выполняются автоматически; останавливается через `disableAutoRun`

**Поля `TaskFsmEntity`:** `stage`, `step`, `stepCount`, `expectedAction`, `paused`, `autoRun`, `savedStage/Step/Action`

**Обработка ошибок:**
- Нет нумерованных шагов в ответе → `isError=true`, FSM → ERROR, показывается `❌`
- `isError=true` сообщения исключаются из `buildHistory`
- При следующем `sendMessage` в состоянии ERROR → сброс в PLANNING

**UI:** `FsmStatusBanner` — цветовая индикация, прогресс-бар, бейдж АВТО. Кнопки: ▶ Run All / ⏸ Stop / ↺ Reset.

### Agent Constraints

Глобально в SharedPreferences. При включении:
- **Pre-check** (`checkConstraintViolation`) — до планирования, запрос отправляется в LLM
- **Post-check** (`checkResponseViolation`) — после каждого ответа FSM
- Нарушение → FSM → ERROR, второй API-вызов для генерации альтернативы
- Формат ошибки: `❌ Ошибка: действие нарушает ограничение «...»` + альтернатива
- `constraints` поле удалено из `UserInformation` — используй `ConstraintsRepository`

### MCP architecture

```kotlin
interface McpProviderFacade {
    val isEnabled: Boolean
    val isConnected: Boolean
    suspend fun connect(): McpConnectionStatus
    fun disconnect()
    suspend fun callTool(toolName: String, arguments: JSONObject): String
}
```

**ВкусВилл** — `https://mcp001.vkusvill.ru/mcp`: полный handshake (`initialize` → `notifications/initialized` → `tools/list`), session ID в заголовке `Mcp-Session-Id`. `McpRepository` — `open` класс.

**Telegram** — `http://10.0.2.2:8080/mcp` (эмулятор): stateless HTTP POST, без handshake, Basic Auth (`mcp` / `MCP_PASSWORD`). `extractResultText` обрабатывает JSONArray и объект с `content`. `stripChatIds` удаляет chat_id из ответа и заполняет `dialogIdMap`. Cleartext разрешён в `network_security_config.xml`.

## Тестирование

**app/src/test — 175 тестов, 0 failures:**
```
AgentRunnerTest.kt           — ReAct loop (16)
AgentRunnerMcpTest.kt        — MCP интеграция (15)
BuildHistoryTest.kt          — 5 стратегий памяти (12)
BuildInstructionsTest.kt     — buildInstructions (11)
SendMessageTest.kt           — sendMessage full flow (11)
BuildBranchHistoryTest.kt    — branching history (9)
AgentMemoryTest.kt           — KV store (10)
UserProfileRepositoryTest.kt — profile/task context (10)
TaskFsmRepositoryTest.kt     — FSM transitions (24)
FsmLLMAgentTest.kt           — FSM интеграция LLMAgent (14)
ConstraintsRepositoryTest.kt — ConstraintsRepository (8)
ConstraintsCheckTest.kt      — pre/post-check (13)
TelegramMcpClientTest.kt     — Telegram MCP (15)
LLMAgentTestBase.kt          — Fake инфраструктура (без тестов)
```

**shared/commonTest — 145 тестов, 0 failures (Android target)**

### Тестовая инфраструктура (shared/commonTest)

Fake-классы в `LLMAgentTestBase.kt`: `FakeSessionRepository`, `FakeMessageRepository`, `FakeSummaryRepository`, `FakeFactRepository`, `FakeBranchNodeRepository`, `FakeTaskFsmRepository`, `FakeKeyValueStorage`, `FakeClock`, `FakeUuidGenerator`, `FakeDateFormatter`

Helpers: `makeFakeMemory(contextString)`, `makeFakeUserProfile(contextString)`, `makeFakeConstraintsRepository()`, `simpleResponse()`, `CapturingAnthropicApi`, `CountingAnthropicApi`, `SequentialAnthropicApi`

**MCP тесты:** `McpRepository` — `open`, наследуй `FakeMcpRepository : McpRepository(null, null)`. Не используй Mockito для suspend-функций McpRepository. `TelegramMcpClientTest` использует `internal` методы напрямую.

**STICKY_FACTS/COMPRESSION:** используй `SequentialAnthropicApi.requests.first()` для захвата главного запроса (не `CapturingAnthropicApi.lastRequest` — он перезаписывается).

## Правила написания кода

### Устранение дублирования
- Extension-функции для операций, встречающихся 2+ раз
- Приватные helpers вместо дублирования внутри класса

### Параметры функций
- Больше 4 параметров → data class (пример: `SessionContextConfig`)
- То же для лямбд `onSave` в Composable

### Корутины в ViewModel
- Группируй связанные Job в `Map<String, Job>`, не в отдельные поля

### Дублирующая логика с вариацией
- Общий private helper, публичные методы формируют только свой prompt/параметры

## Ключевые ограничения

- **API key:** `local.properties` → `PROXY_API_KEY` → `BuildConfig.PROXY_API_KEY` → `di/AppModule.kt`. Backend: `https://api.proxyapi.ru/openai/v1/`
- **Telegram MCP password:** `local.properties` → `TELEGRAM_MCP_PASSWORD` → `BuildConfig.TELEGRAM_MCP_PASSWORD`
- **MCP SDK:** `io.modelcontextprotocol:kotlin-sdk-client:0.9.0` + Ktor 3.2.3 (server artifacts excluded)
- **Room:** destructive migration — схема меняется, данные теряются
- **`AgentMemory`** — global, shared across all sessions; `open class`, `open fun toContextString()`
- **`UserProfileRepository`** — global SharedPreferences; `open class`, `open fun toContextString()`; `constraints` удалён из `UserInformation`
- **`ConstraintsRepository`** — два синхронных API-вызова на каждый FSM-шаг, добавляет латентность
- **`STICKY_FACTS` и `COMPRESSION`** — дополнительный API-вызов внутри `sendMessage`
- **Session title** — автоустанавливается из первого предложения первого ответа ассистента
- **Kotlin 2.1.0**, `jvmTarget = "11"`, KSP 2.1.0-1.0.29
- **JS target:** `kotlin.incremental.js.ir=false` в `gradle.properties` (баг Kotlin 2.1.0 IC)
- **`removeIf`** — не использовать в commonMain/commonTest (нет в Kotlin/JS); заменять на `indexOfFirst/removeAt`
- **`assertTrue(condition, message)`** — в kotlin.test сообщение второй параметр (не первый как в JUnit4)
- Без Mockito в `shared/commonTest` — только Fake-классы
