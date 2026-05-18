# Android Tech Plan: MCP Composition (BTC multi-server flow)
Версия: 1.0
Дата: 2026-05-17
Источник: docs/specs/mcp-composition.md + docs/adr/mcp-composition.md + docs/plans/plan-backend-mcp-composition.md

## Стек
- **shared/commonMain** — `AgentRunner` НЕ меняется (см. ADR строка 47, ниже Анализ существующего кода).
- **app/** — Jetpack Compose, Koin DI, OkHttp, SharedPreferences. Никаких Room/Retrofit/Hilt.
- **Тесты** — JUnit4 + Mockito-kotlin (как все существующие тесты в `app/src/test/`). org.json уже в `testImplementation`.

## Анализ существующего кода (что уже есть)

| Компонент | Файл / линия | Что есть |
|-----------|--------------|----------|
| `AgentRunner` (shared) | `shared/src/commonMain/kotlin/com/example/myapplication/agent/AgentRunner.kt:93-97` | `vararg val mcpProviders: McpProviderFacade`. `toolToProvider` собирается в `run()` через `connect()` всех провайдеров — поддержка N серверов готова. |
| `CryptoMcpRepository` | `app/src/main/java/com/example/myapplication/data/reminder/CryptoMcpRepository.kt:21-30` | `open class : McpProviderFacade`, `var cryptoEnabled` (default false), `connect()`/`callTool()`/`disconnect()` готовы. Подходит для FakeCryptoMcpRepository. |
| `StatelessMcpRepository(taskSave)` | `app/src/main/java/com/example/myapplication/data/mcp/StatelessMcpRepository.kt:7`, `di/AppModule.kt:124` | `open class`, `var enabled` (default true). prefName = `task_mcp_prefs`, prefKey = `task_save_enabled`. Подходит для FakeSaveMcpRepository. |
| `AgentViewModel` зависимости | `app/.../presentation/agent/AgentViewModel.kt:73-85`, `di/AppModule.kt:138` | Уже принимает `cryptoMcpRepository`, `taskSaveMcpRepository` (named "taskSave"). |
| `LLMAgent.saveAssistantMessage` | `shared/.../agent/LLMAgent.kt:986` | `suspend fun saveAssistantMessage(sessionId: String, text: String, branchNodeId: String? = null)` — готов к использованию. |
| `ReminderForegroundService` | `app/.../service/ReminderForegroundService.kt:49-72` | `startSse()` уже слушает `reminderManager.connectFlow().collect { event -> ... }` (строки 58-63). Сюда вставляется триггер. |
| `AgentScreen` settings sheet | `app/.../presentation/agent/AgentScreen.kt:135-167, 357-377, 880-942` | `ContextSettingsSheet` — точка добавления toggle «BTC композиция». Образец: блок «Task Save (8083)» строки 910-942. |

## Архитектурное решение для bridge SSE → ViewModel (выбор Spec строки 127-130)

**Выбран вариант (b): вынести `runBtcCompositionFlow()` в отдельный `CompositionFlowUseCase` (singleton в Koin).**

**Обоснование:**
- `ReminderForegroundService` — `Service` без ViewModelStore. `AgentViewModel.viewModelScope` исчезает при уничтожении ViewModel. Service не должен зависеть от жизненного цикла Activity.
- `CompositionFlowUseCase` как singleton живёт всё время жизни приложения, у него собственный `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- Use-case инъектируется и в Service (через `by inject()`), и в `AgentViewModel` (через конструктор) — оба вызывают один и тот же метод.
- Use-case хранит `Mutex` (single-flight) и читает `_activeSessionId` из `AgentViewModel` через инжекцию контракта (см. ниже SH-02).
- Spec строка 127-130 явно оставляет выбор за planner-ом.

**Как `CompositionFlowUseCase` узнаёт `_activeSessionId`:** через интерфейс `ActiveSessionProvider` (см. SH-02), который реализует `AgentViewModel`. ViewModel регистрирует себя в use-case (или use-case инжектирует callback при создании). Конкретная регистрация — через метод `bind(provider: ActiveSessionProvider)` в use-case, вызываемый из `AgentViewModel.init`.

## Задачи: Android Shared Dev (shared/)

| ID    | Задача | DoD | Зависимости |
|-------|--------|-----|-------------|
| SH-01 | Никаких изменений в `AgentRunner` — verify-test | Существующий тест `AgentRunnerMcpTest` зелёный после рефакторинга (если потребуется). Контракт `vararg McpProviderFacade` и `toolToProvider` НЕ меняется (см. ADR строка 47, 69). | — |
| SH-02 | Доменный интерфейс `ActiveSessionProvider` (только если выбран вариант UseCase) | `interface ActiveSessionProvider { val activeSessionId: String? }` создан в `shared/src/commonMain/kotlin/com/example/myapplication/agent/ActiveSessionProvider.kt`. | — |

**Примечание:** SH-задач минимум по требованию ADR строка 68-70 («Никаких изменений в commonMain — флоу собирается из существующих интерфейсов»). SH-02 — это маленький интерфейс на стыке слоёв; альтернативно его можно положить в `app/` (UI слой), и тогда SH-задач остаётся 1.

## Задачи: Android UI Dev (app/)

| ID    | Задача | DoD | Зависимости |
|-------|--------|-----|-------------|
| UI-01 | `BtcCompositionSettings` — обёртка над `SharedPreferences("mcp_composition_prefs")` с ключом `btc_composition_enabled` (default false) | Класс создан в `app/src/main/java/com/example/myapplication/data/composition/BtcCompositionSettings.kt`. `open class`, `var enabled: Boolean` getter/setter через prefs. | — |
| UI-02 | `BTC_COMPOSITION_PROMPT` — константа промпта длинного флоу из ADR строки 91-116 | `const val BTC_COMPOSITION_PROMPT` в `app/.../data/composition/BtcCompositionPrompt.kt`. Текст ТОЧНО как в ADR — 6 шагов, явное имя `get_price`, шаблон имени `btc-snapshot-<unix-millis>.txt`, два варианта FINAL_ANSWER. | — |
| UI-03 | `CompositionFlowUseCase` — singleton с `runBtcCompositionFlow()` | Класс создан в `app/src/main/java/com/example/myapplication/usecase/CompositionFlowUseCase.kt`. Содержит `Mutex` (single-flight), `bind(ActiveSessionProvider)`, suspend `runBtcCompositionFlow()`. Логика по Spec строки 119-124. | UI-01 ✅, UI-02 ✅, SH-02 ✅ |
| UI-04 | Koin регистрация `BtcCompositionSettings` + `CompositionFlowUseCase` | `single { BtcCompositionSettings(androidContext()) }` и `single { CompositionFlowUseCase(get(), get(), get(), get(), get()) }` добавлены в `di/AppModule.kt` после строки 124. | UI-01 ✅, UI-03 ✅ |
| UI-05 | `AgentViewModel` — добавить `BtcCompositionSettings` и `CompositionFlowUseCase` в конструктор; вызов `compositionFlowUseCase.bind(this)` в `init`; toggle-методы `toggleBtcComposition(enabled: Boolean)`, поле `btcCompositionEnabled` в `AgentUiState`, `refreshBtcCompositionState()` | Конструктор расширен. Реализация `ActiveSessionProvider` (`override val activeSessionId: String? get() = _activeSessionId.value`). `init` зовёт `compositionFlowUseCase.bind(this)`. `AgentUiState` получает поле `btcCompositionEnabled: Boolean = false`. Метод `toggleBtcComposition` пишет в settings и обновляет state. | UI-01 ✅, UI-03 ✅, UI-04 ✅ |
| UI-06 | `ReminderForegroundService` — инжектить `CompositionFlowUseCase`, вызывать `runBtcCompositionFlow()` после `emitReminder(event)` | `private val compositionFlowUseCase: CompositionFlowUseCase by inject()` добавлен в Service. В `startSse()` после строки 62 (`reminderManager.emitReminder(event)`) добавлен вызов `serviceScope.launch { compositionFlowUseCase.runBtcCompositionFlow() }`. | UI-03 ✅, UI-04 ✅ |
| UI-07 | `ContextSettingsSheet` — добавить toggle «BTC композиция (Crypto MCP → save → diff)» | В `AgentScreen.kt` `ContextSettingsSheet` получает параметры `btcCompositionEnabled: Boolean` и `onToggleBtcComposition: (Boolean) -> Unit`. После блока Task Save (строка 942) добавлен Row с Switch (образец: строки 910-942). В `AgentScreen` (строка 152) передаются эти параметры из uiState и viewModel. | UI-05 ✅ |
| UI-08 | TDD: unit-тесты `CompositionFlowUseCaseTest` (минимум 5 по AC-10) | Файл `app/src/test/java/com/example/myapplication/CompositionFlowUseCaseTest.kt`. См. секцию «Тестовая стратегия» ниже. `./gradlew :app:testDebugUnitTest` → 0 failures, ≥ 180 общих тестов (было 175 + 5 новых). | UI-03 ✅ |

**Итого:** 2 SH-задачи + 8 UI-задач = 10 задач. Минимум новых тестов: 5 (AC-10).

## Файлы для создания / изменения

### Новые файлы

| Файл | Назначение |
|------|------------|
| `shared/src/commonMain/kotlin/com/example/myapplication/agent/ActiveSessionProvider.kt` | Интерфейс контракта для use-case (SH-02). |
| `app/src/main/java/com/example/myapplication/data/composition/BtcCompositionSettings.kt` | SharedPreferences обёртка `btc_composition_enabled` (UI-01). |
| `app/src/main/java/com/example/myapplication/data/composition/BtcCompositionPrompt.kt` | `const val BTC_COMPOSITION_PROMPT` (UI-02). |
| `app/src/main/java/com/example/myapplication/usecase/CompositionFlowUseCase.kt` | Use-case `runBtcCompositionFlow()` с Mutex (UI-03). |
| `app/src/test/java/com/example/myapplication/CompositionFlowUseCaseTest.kt` | 5+ unit-тестов (UI-08). |

### Изменяемые файлы

| Файл | Действие | Что менять |
|------|----------|------------|
| `app/src/main/java/com/example/myapplication/di/AppModule.kt` | EDIT | + `single { BtcCompositionSettings(androidContext()) }` после строки 124. + `single { CompositionFlowUseCase(get(), get(), get<CryptoMcpRepository>(), get<StatelessMcpRepository>(named("taskSave")), get()) }`. Конструктор `viewModel { AgentViewModel(...) }` (строка 138) расширить двумя зависимостями. |
| `app/src/main/java/com/example/myapplication/presentation/agent/AgentViewModel.kt` | EDIT | + параметры в конструктор: `btcCompositionSettings: BtcCompositionSettings, compositionFlowUseCase: CompositionFlowUseCase`. + поле `btcCompositionEnabled: Boolean = false` в `AgentUiState` (строка 44-71). + `override val activeSessionId: String? get() = _activeSessionId.value` (реализация `ActiveSessionProvider`). + `compositionFlowUseCase.bind(this)` в `init` (строка 106-126). + методы `toggleBtcComposition(enabled: Boolean)` и `refreshBtcCompositionState()`. |
| `app/src/main/java/com/example/myapplication/service/ReminderForegroundService.kt` | EDIT | + `import com.example.myapplication.usecase.CompositionFlowUseCase`. + `private val compositionFlowUseCase: CompositionFlowUseCase by inject()` после строки 25. В `startSse()` после строки 62 — `serviceScope.launch { compositionFlowUseCase.runBtcCompositionFlow() }`. |
| `app/src/main/java/com/example/myapplication/presentation/agent/AgentScreen.kt` | EDIT | + 2 параметра в `ContextSettingsSheet` (строка 347-377): `btcCompositionEnabled: Boolean`, `onToggleBtcComposition: (Boolean) -> Unit`. + Row с Switch после строки 942 (по образцу блока Task Save). + 2 строки в `AgentScreen` (после строки 152): `btcCompositionEnabled = uiState.btcCompositionEnabled, onToggleBtcComposition = { viewModel.toggleBtcComposition(it) }`. |

### НЕ менять
- `AgentRunner.kt` (shared) — контракт `vararg McpProviderFacade` уже поддерживает N серверов (ADR строка 69).
- `StatelessMcpClient.kt` / `StatelessMcpRepository.kt` — никаких изменений на клиенте (ADR строка 44).
- `CryptoMcpRepository.kt` — никаких изменений (ADR строка 86).
- `LLMAgent.kt` — `saveAssistantMessage` уже существует (строка 986).
- `network_security_config.xml` — cleartext для `10.0.2.2:8080` и `10.0.2.2:8083` уже разрешён (Spec строка 144).
- `local.properties` / `BuildConfig` — никаких новых ключей.
- Room schema / DAO / Entity — никаких изменений (ADR строка 64, 85).

## Сигнатуры

### shared/commonMain (SH-02)

```kotlin
// shared/src/commonMain/kotlin/com/example/myapplication/agent/ActiveSessionProvider.kt
package com.example.myapplication.agent

interface ActiveSessionProvider {
    val activeSessionId: String?
}
```

### app/ (UI-01)

```kotlin
// app/src/main/java/com/example/myapplication/data/composition/BtcCompositionSettings.kt
package com.example.myapplication.data.composition

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "mcp_composition_prefs"
private const val KEY_BTC_COMPOSITION_ENABLED = "btc_composition_enabled"

open class BtcCompositionSettings(context: Context?) {
    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    open var enabled: Boolean
        get() = prefs?.getBoolean(KEY_BTC_COMPOSITION_ENABLED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_BTC_COMPOSITION_ENABLED, value)?.apply() }
}
```

### app/ (UI-02)

```kotlin
// app/src/main/java/com/example/myapplication/data/composition/BtcCompositionPrompt.kt
package com.example.myapplication.data.composition

/**
 * Точный промпт длинного флоу — копия из docs/adr/mcp-composition.md строки 91-116.
 * Менять только синхронно с ADR.
 */
const val BTC_COMPOSITION_PROMPT: String = """Ты выполняешь сквозной BTC-флоу. Используй только инструменты из tools/list, доступные тебе.

1. Вызови инструмент get_price с {"symbol":"BTC"} — он на Crypto MCP сервере.
   Сохрани полученный текст ответа (формат "Current price of BTC: ${'$'}<число> USD").

2. Вызови save_to_file на save-сервере с
   filename = "btc-snapshot-<unix-millis>.txt"  (millis = текущий UNIX-time в миллисекундах)
   content  = <текст из шага 1>

3. Вызови list_files на save-сервере без аргументов.
   Найди в списке все файлы с префиксом "btc-snapshot-".

4. Если таких файлов ровно один — твой FINAL_ANSWER:
   "BTC snapshot saved. Дельта недоступна (первый снимок)."
   и больше ничего не вызывай.

5. Если файлов >= 2 — возьми два с наибольшими mtime (первые два в отсортированном по убыванию списке):
   текущий (только что сохранённый) и предыдущий.
   Вызови read_file для предыдущего файла, получи прошлую цену.
   Извлеки число из строки "Current price of BTC: ${'$'}<число> USD" в обоих файлах.
   Посчитай дельту = текущая - предыдущая.

6. FINAL_ANSWER в формате:
   "BTC: <текущая>, было <предыдущая>, дельта <+X | -X>""""
```

### app/ (UI-03)

```kotlin
// app/src/main/java/com/example/myapplication/usecase/CompositionFlowUseCase.kt
package com.example.myapplication.usecase

import com.example.myapplication.agent.ActiveSessionProvider
import com.example.myapplication.agent.AgentRunner
import com.example.myapplication.agent.AgentStepType
import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.composition.BTC_COMPOSITION_PROMPT
import com.example.myapplication.data.composition.BtcCompositionSettings
import com.example.myapplication.data.mcp.StatelessMcpRepository
import com.example.myapplication.data.reminder.CryptoMcpRepository
import kotlinx.coroutines.sync.Mutex

open class CompositionFlowUseCase(
    private val agent: LLMAgent,
    private val agentRunner: AgentRunner,
    private val cryptoMcpRepository: CryptoMcpRepository,
    private val taskSaveMcpRepository: StatelessMcpRepository,
    private val btcCompositionSettings: BtcCompositionSettings
) {
    private val compositionMutex = Mutex()
    private var sessionProvider: ActiveSessionProvider? = null

    fun bind(provider: ActiveSessionProvider) {
        sessionProvider = provider
    }

    /**
     * Идемпотентный single-flight запуск BTC-композиции.
     * Поведение (AC-01, AC-07, AC-08):
     *   - skip если флаги выключены или нет активной сессии → Log.d("BtcComposition", "skip: <reason>")
     *   - если Mutex уже захвачен → return (новое событие пропускается)
     *   - после run → если последний step.type == FINAL_ANSWER → saveAssistantMessage
     *   - при исключении → Log.e("BtcComposition", "Composition flow failed: <reason>", e), в чат НЕ пишется
     */
    open suspend fun runBtcCompositionFlow() {
        // 1. Флаг включён?
        if (!btcCompositionSettings.enabled) {
            android.util.Log.d("BtcComposition", "skip: btc_composition_enabled=false")
            return
        }
        // 2. Оба провайдера включены?
        if (!cryptoMcpRepository.cryptoEnabled || !taskSaveMcpRepository.enabled) {
            android.util.Log.d("BtcComposition", "skip: cryptoEnabled=${cryptoMcpRepository.cryptoEnabled}, taskSave.enabled=${taskSaveMcpRepository.enabled}")
            return
        }
        // 3. Есть активная сессия?
        val sessionId = sessionProvider?.activeSessionId
        if (sessionId == null) {
            android.util.Log.d("BtcComposition", "skip: no active session")
            return
        }
        // 4. Single-flight
        if (!compositionMutex.tryLock()) {
            android.util.Log.d("BtcComposition", "skip: previous run still in progress")
            return
        }
        try {
            android.util.Log.d("BtcComposition", "start: sessionId=$sessionId")
            var finalAnswer: String? = null
            agentRunner.run(BTC_COMPOSITION_PROMPT) { step ->
                if (step.type == AgentStepType.FINAL_ANSWER) {
                    finalAnswer = step.content
                }
            }
            val answer = finalAnswer
            if (answer != null && !answer.startsWith("Error:")) {
                agent.saveAssistantMessage(sessionId, answer, branchNodeId = null)
                android.util.Log.d("BtcComposition", "success: $answer")
            } else {
                android.util.Log.e("BtcComposition", "Composition flow failed: no FINAL_ANSWER or error answer ($answer)")
            }
        } catch (e: Exception) {
            android.util.Log.e("BtcComposition", "Composition flow failed: ${e.message}", e)
        } finally {
            compositionMutex.unlock()
        }
    }
}
```

### app/ (UI-05) — AgentViewModel изменения

```kotlin
// app/src/main/java/com/example/myapplication/presentation/agent/AgentViewModel.kt
class AgentViewModel(
    private val agent: LLMAgent,
    private val userProfileRepository: UserProfileRepository,
    private val constraintsRepository: ConstraintsRepository,
    private val mcpRepository: McpRepository,
    private val agentRunner: AgentRunner,
    private val telegramMcpRepository: TelegramMcpRepository,
    private val reminderRepository: ReminderManager,
    private val cryptoMcpRepository: CryptoMcpRepository,
    private val taskSearchMcpRepository: StatelessMcpRepository,
    private val taskSummarizeMcpRepository: StatelessMcpRepository,
    private val taskSaveMcpRepository: StatelessMcpRepository,
    private val btcCompositionSettings: BtcCompositionSettings,           // НОВОЕ
    private val compositionFlowUseCase: CompositionFlowUseCase            // НОВОЕ
) : ViewModel(), ActiveSessionProvider {                                  // + интерфейс

    override val activeSessionId: String? get() = _activeSessionId.value  // НОВОЕ

    init {
        compositionFlowUseCase.bind(this)                                 // НОВОЕ
        refreshBtcCompositionState()                                      // НОВОЕ
        // ... остальное без изменений
    }

    fun toggleBtcComposition(enabled: Boolean) {
        btcCompositionSettings.enabled = enabled
        _uiState.update { it.copy(btcCompositionEnabled = enabled) }
    }

    private fun refreshBtcCompositionState() {
        _uiState.update { it.copy(btcCompositionEnabled = btcCompositionSettings.enabled) }
    }
}

data class AgentUiState(
    // ... все существующие поля ...
    val btcCompositionEnabled: Boolean = false  // НОВОЕ
)
```

### app/ (UI-06) — ReminderForegroundService изменения

```kotlin
// app/src/main/java/com/example/myapplication/service/ReminderForegroundService.kt
import com.example.myapplication.usecase.CompositionFlowUseCase  // НОВЫЙ импорт

class ReminderForegroundService : Service() {
    private val reminderManager: ReminderManager by inject()
    private val compositionFlowUseCase: CompositionFlowUseCase by inject()  // НОВОЕ

    // ... остальное без изменений до startSse() ...

    private fun startSse() {
        sseJob?.cancel()
        sseJob = serviceScope.launch {
            var attempt = 0
            while (true) {
                attempt++
                // ... без изменений ...
                try {
                    reminderManager.connectFlow().collect { event ->
                        // ... без изменений до строки 62 ...
                        reminderManager.emitReminder(event)
                        // НОВОЕ — single-flight внутри use-case, fire-and-forget:
                        serviceScope.launch { compositionFlowUseCase.runBtcCompositionFlow() }
                    }
                    // ... без изменений ...
                }
                // ... без изменений ...
            }
        }
    }
}
```

### app/ (UI-07) — AgentScreen изменения

В `AgentScreen` (вызов `ContextSettingsSheet`, строки 135-167) добавить:
```kotlin
btcCompositionEnabled = uiState.btcCompositionEnabled,
onToggleBtcComposition = { viewModel.toggleBtcComposition(it) },
```

В `ContextSettingsSheet` signature (строки 347-377) добавить 2 параметра:
```kotlin
btcCompositionEnabled: Boolean,
onToggleBtcComposition: (Boolean) -> Unit,
```

Внутри `ContextSettingsSheet`, после блока «Task Save (8083)» (строка 942) добавить новый Row (точная копия структуры строк 910-942), с заголовком `Text("BTC композиция (Crypto MCP → save → diff)")`, без status-line (просто toggle), `Switch(checked = btcCompositionEnabled, onCheckedChange = { onToggleBtcComposition(it) })`.

## DTO / data классы

Никаких новых DTO. Все данные — plain String (текст от MCP-tool, текст FINAL_ANSWER). Источник истины — файлы на save-server (ADR строка 63).

## UIState

`AgentUiState` (`AgentViewModel.kt:44-71`) расширяется одним полем:

```kotlin
val btcCompositionEnabled: Boolean = false
```

Отдельный `BtcCompositionUiState` НЕ нужен — результат отображается в существующих `messages: StateFlow<List<Message>>` через `saveAssistantMessage`. Никаких Loading/Error состояний в UI (Spec строки 44-48 — Loading: «видимых изменений нет», Error: «в чат ничего не пишется»).

## Зона `_activeSessionId == null` (Spec строка 17, ADR строка 157)

`CompositionFlowUseCase.runBtcCompositionFlow()` молча выходит с `Log.d("BtcComposition", "skip: no active session")`. Foreground-notification не меняется. Это явный default из ADR.

## Тестовая стратегия

**Файл:** `app/src/test/java/com/example/myapplication/CompositionFlowUseCaseTest.kt`

**Инфраструктура:**
- JUnit4 + mockito-kotlin (как все существующие тесты).
- `FakeCryptoMcpRepository : CryptoMcpRepository(null, null)` — подкласс с переопределёнными `cryptoEnabled`, `connect()`, `callTool()`. (Класс `open` уже — см. `CryptoMcpRepository.kt:21`.)
- `FakeSaveMcpRepository : StatelessMcpRepository(null, FakeStatelessMcpClient(), "task_save_enabled")` — подкласс с переопределённым `enabled`. (Класс `open` уже.) Или передавать настоящий `StatelessMcpClient` к моку OkHttpClient.
- `FakeBtcCompositionSettings : BtcCompositionSettings(null)` — подкласс с переопределённым `enabled`. (Класс `open` — см. SH/UI-01 сигнатура.)
- `FakeActiveSessionProvider(override val activeSessionId: String?) : ActiveSessionProvider`.
- `LLMAgent`, `AgentRunner` — mocked через `mock<LLMAgent>()` / `mock<AgentRunner>()`. `AgentRunner.run` — `whenever(runner.run(any(), any())).thenAnswer { ... emit FINAL_ANSWER step ... }`.

**Тесты (минимум 5 по AC-10):**

| #  | Тест                                                                                       | AC      |
|----|--------------------------------------------------------------------------------------------|---------|
| 1  | `runBtcCompositionFlow skips when btc_composition_enabled=false`                          | AC-08   |
| 2  | `runBtcCompositionFlow skips when cryptoEnabled=false`                                    | AC-08   |
| 3  | `runBtcCompositionFlow skips when taskSave enabled=false`                                 | AC-08   |
| 4  | `runBtcCompositionFlow skips when activeSessionId is null`                                | AC-08   |
| 5  | `single-flight: second concurrent call is no-op (mutex)`                                  | AC-01   |
| 6  | `successful run with single snapshot saves first-snapshot text via saveAssistantMessage`  | AC-05   |
| 7  | `save_to_file error: nothing written to chat, error logged`                               | AC-07   |
| 8  | (опционально) `successful run with delta saves "BTC: X, было Y, дельта Z" via saveAssistantMessage` | AC-06   |

**Проверка вызовов:**
```kotlin
verify(agent, never()).saveAssistantMessage(any(), any(), anyOrNull())  // для skip-тестов
verify(agent).saveAssistantMessage(eq("session-1"), eq("BTC snapshot saved. Дельта недоступна (первый снимок)."), anyOrNull())  // для AC-05
verify(agentRunner, never()).run(any(), any())  // для skip-тестов
```

**Запуск:**
```bash
./gradlew :app:testDebugUnitTest
```
Ожидание: 0 failures, ≥ 180 тестов (175 старых + 5 новых обязательных + опциональные).

## AC покрытие

| AC      | Источник (Spec)                                                                    | Android-задача          | Как проверяется                                                                  |
|---------|------------------------------------------------------------------------------------|-------------------------|----------------------------------------------------------------------------------|
| AC-01   | Триггер из SSE, single-flight через Mutex                                          | UI-06, UI-03            | Тест `single-flight: second concurrent call is no-op` (UI-08 тест #5)            |
| AC-02   | `get_price` с `{"symbol":"BTC"}` на Crypto MCP                                     | UI-02 (промпт), UI-03   | Косвенно — промпт инструктирует LLM; покрыто live-прогоном.                       |
| AC-03   | `save_to_file` после `get_price`                                                   | UI-02 (промпт), UI-03   | Косвенно — промпт; backend BE-07 верифицирует регрессию `save_to_file`.          |
| AC-04   | `list_files` без аргументов, сортировка mtime DESC                                 | UI-02 (промпт)          | Backend гарантирует формат (plan-backend BE-02, BE-05, BE-06).                   |
| AC-05   | Один файл → `BTC snapshot saved. Дельта недоступна (первый снимок).`              | UI-03                   | Тест #6 (UI-08): `saveAssistantMessage` вызван с точной строкой.                  |
| AC-06   | ≥ 2 файла → `BTC: X, было Y, дельта ±Z`                                            | UI-02 (промпт), UI-03   | Опциональный тест #8; основная проверка — live-прогон.                            |
| AC-07   | Ошибка любого tool → Log.e, в чат не пишется                                       | UI-03                   | Тест #7 (UI-08): `verify(agent, never()).saveAssistantMessage(...)`.              |
| AC-08   | Выключенные флаги / нет сессии → mgновенный return, Log.d                          | UI-03                   | Тесты #1, #2, #3, #4 (UI-08).                                                     |
| AC-09   | Роутинг: `get_price`/`get_price_history`/... → Crypto; `save_to_file`/`list_files`/`read_file` → save | (Без новых задач — `AgentRunner.toolToProvider` уже работает) | Покрыто существующим `AgentRunnerMcpTest`. |
| AC-10   | Минимум 5 unit-тестов                                                              | UI-08                   | `CompositionFlowUseCaseTest` — 5 обязательных + 2 опциональных.                  |
| AC-11   | Backend тесты save-server                                                          | (Не Android)            | Покрыто в plan-backend BE-01, BE-03, BE-05, BE-06.                                |

**Покрытие: 11/11 AC** (AC-11 — backend, не Android Tech Plan).

## Порядок выполнения (TDD)

```
UI-01 (BtcCompositionSettings) — без зависимостей
   ↓
UI-02 (промпт-константа) — без зависимостей
   ↓
SH-02 (ActiveSessionProvider интерфейс) — без зависимостей
   ↓
UI-03 (CompositionFlowUseCase) — зависит от UI-01, UI-02, SH-02
   ↓
UI-08 (TDD: написать тесты для UseCase ПЕРЕД полной интеграцией)
   ↓
UI-04 (Koin регистрация)
   ↓
UI-05 (AgentViewModel: bind + toggle + UiState поле)
   ↓
UI-06 (ReminderForegroundService bridge)         ← параллельно с UI-07
UI-07 (AgentScreen toggle UI)                    ← параллельно с UI-06
   ↓
Финальный регресс: ./gradlew :app:testDebugUnitTest + ручной прогон на эмуляторе
```

**TDD-flow для UI-03 + UI-08:**
1. RED — написать `CompositionFlowUseCaseTest` с 5 тестами; они падают (use-case ещё нет).
2. GREEN — реализовать `CompositionFlowUseCase`; тесты зелёные.
3. REFACTOR — извлечь повторяющиеся проверки skip в `private fun checkPrerequisites(): String?` если уместно (CLAUDE.md → Coding Guidelines → Устранение дублирования).

## Команды для разработчиков

```bash
# Валидация плана разработчиками перед стартом
bash .claude/hooks/validate-against-plan.sh mcp-composition shared
bash .claude/hooks/validate-against-plan.sh mcp-composition ui

# Полный регресс
./gradlew :app:testDebugUnitTest

# Сборка debug APK для ручной проверки
./gradlew assembleDebug

# Запуск backend save-server для интеграции (см. plan-backend):
# cd C:\Users\sutug\IdeaProjects\MCP-mock-composition-tool-server
# java -jar save-server/build/libs/save-server.jar
```

## REWORK_1 — Исправления по review итерация 1
Дата: 2026-05-17
Источник: docs/reviews/mcp-composition-review-1.md

### UI-03-FIX: Отдельный именованный AgentRunner в Koin (ВАЖНО)

Цитата из review: "CompositionFlowUseCase получает тот же AgentRunner singleton что и AgentViewModel. Общая conversationHistory загрязняет контекст обычных диалогов пользователя."

Действие — в `di/AppModule.kt` добавить отдельный named singleton:
```kotlin
single(named("composition")) {
    AgentRunner(get(), get(), get<CryptoMcpRepository>(), get<StatelessMcpRepository>(named("taskSave")))
}
```
Конструктор `CompositionFlowUseCase` в Koin переключить с `get<AgentRunner>()` на `get<AgentRunner>(named("composition"))`:
```kotlin
single { CompositionFlowUseCase(get(), get(named("composition")), get<CryptoMcpRepository>(), get<StatelessMcpRepository>(named("taskSave")), get()) }
```
Файл: `app/src/main/java/com/example/myapplication/di/AppModule.kt`

### UI-06-FIX: SupervisorJob() в ReminderForegroundService (ВАЖНО)

Цитата из review: "При падении корутина compositionFlowUseCase.runBtcCompositionFlow() весь serviceScope будет отменён и SSE-поток остановится."

Действие — в `ReminderForegroundService.kt` строка 29 заменить `Job()` на `SupervisorJob()`:
```kotlin
// БЫЛО:
private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
// СТАЛО:
private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
```
Файл: `app/src/main/java/com/example/myapplication/service/ReminderForegroundService.kt`

### UI-08-FIX: AgentRunner с провайдерами в тестах + async single-flight (КРИТИЧНО + ВАЖНО)

**Исправление 1 — AgentRunner создаётся с провайдерами (КРИТИЧНО)**

Цитата из review: "В makeUseCase() создаётся AgentRunner(api, makeMemory()) — без crypto и save провайдеров. Тест №5 не проверяет реальную логику роутинга через toolToProvider."

Действие — в `CompositionFlowUseCaseTest.kt` в функции `makeUseCase()` (строка 80):
```kotlin
// БЫЛО:
private fun makeUseCase(...): CompositionFlowUseCase {
    val runner = AgentRunner(api, makeMemory())  // без провайдеров
    ...
}
// СТАЛО:
private fun makeUseCase(...): CompositionFlowUseCase {
    val runner = AgentRunner(api, makeMemory(), crypto, save)  // с провайдерами
    ...
}
```
Это гарантирует что `connect()` вызывается на обоих провайдерах при `runner.run(...)`.

**Исправление 2 — Тест single-flight с async конкурентными вызовами (ВАЖНО)**

Цитата из review: "Тест запускает два последовательных вызова — не проверяет конкурентную single-flight семантику."

Действие — переписать тест #5 `single-flight: second concurrent call is no-op`:
```kotlin
@Test
fun `single-flight second concurrent call is no-op`() = runTest {
    // Первый вызов блокируется (imitate long run)
    whenever(agentRunner.run(any(), any())).coAnswers {
        delay(100)
        Unit
    }
    val job1 = async { uc.runBtcCompositionFlow() }
    val job2 = async { uc.runBtcCompositionFlow() }
    awaitAll(job1, job2)
    verify(agentRunner, times(1)).run(any(), any())
}
```
Файл: `app/src/test/java/com/example/myapplication/CompositionFlowUseCaseTest.kt`

---

## Открытые вопросы / решения planner-а

⚠️ НЕЯСНО (низкий приоритет, дефолты применены):

1. **Архитектурный выбор bridge SSE → ViewModel:** Spec строки 127-130 оставил выбор между (a) ViewModel as singleton в Koin и (b) выделенный `CompositionFlowUseCase`. **Выбран (b)** — обоснование выше («Архитектурное решение»). Это не блокирует — если разработчик предложит (a) с обоснованием, можно переключиться без переделки внешнего API.
2. **Префикс `[BTC composition]` в сообщении чата:** ADR строка 158 оставляет на усмотрение. **Дефолт: нет префикса** — обычное assistant-сообщение. Если потребуется, легко добавить как `"[BTC composition] $finalAnswer"` в `CompositionFlowUseCase.runBtcCompositionFlow()` — изменение в одной строке.
3. **Placeholder-сообщение «BTC композиция: считаю...»:** Spec строка 44 «не обязательно для MVP». **Дефолт: не добавлять** — лишние пустые сообщения мешают истории.
4. **Что считать «success» для `saveAssistantMessage`:** Если FINAL_ANSWER пустой или начинается с `Error:` — в чат НЕ писать (см. реализация `CompositionFlowUseCase` выше). Решение planner-а: достаточно проверки `answer != null && !answer.startsWith("Error:")`.
5. **`taskSaveMcpRepository.enabled` default = true** (см. `StatelessMcpRepository.kt:17` — `prefs?.getBoolean(prefKey, true) ?: true`). Если пользователь не трогал toggle Task Save в UI — флоу будет работать. Это согласуется с Spec строкой 17 (нужно `taskSave.enabled == true`).

Все блокирующие решения зафиксированы в ADR — открытых вопросов нет.

## Связь с backend планом

Android-сторона зависит от выполнения следующих BE-задач из `plan-backend-mcp-composition.md`:

- **BE-02** (`listFiles()` имплементация) — без неё AC-04 не покрыт.
- **BE-04** (`readFile(name)` имплементация) — без неё AC-06 не покрыт.
- **BE-05** (toolsList дескрипторы) — без них `AgentRunner.connect()` не получит `list_files`/`read_file` в `mcpTools` и LLM не узнает о них.
- **BE-06** (toolCall ветки) — без них `callTool("list_files"/"read_file", ...)` вернёт `"Unknown tool"`.

Android можно начинать **параллельно** с backend (UI-01, UI-02, SH-02, UI-03 + тесты с моками не требуют живого backend-а), но **интеграционный прогон AC-04/AC-06 невозможен** до BE-07 (полный регресс backend).
