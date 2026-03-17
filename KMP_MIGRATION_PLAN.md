# KMP Migration Plan

Подготовка проекта MyApplication к миграции на Kotlin Multiplatform с последующим созданием Web клиента.

---

## Обзор

**Цель:** вынести бизнес-логику в shared KMP модуль. Android UI не переписывается. В будущем — Web клиент.

**Принцип:** не начинать создание `:shared` модуля, пока не введены все интерфейсы и не написаны baseline тесты.

---

## Целевая структура модулей

```
MyApplication/
├── shared/                     # KMP модуль (новый)
│   └── src/
│       ├── commonMain/         # domain, agent, api-модели, интерфейсы платформы
│       ├── androidMain/        # actual-реализации для Android
│       └── jsMain/             # actual-реализации для Web
├── app/                        # Android app → зависит от :shared
└── webClient/                  # будущий Web клиент (Compose for Web / React)
```

### Граница shared слоя

```
SHARED (commonMain)
  domain/model/        — Session, Message, SummaryData, FactData, BranchNode
  domain/repository/   — интерфейсы: SessionRepository, MessageRepository и др.
  domain/usecase/      — SendMessageUseCase
  agent/               — LLMAgent, AgentRunner, AgentStep
  data/api/model/      — ChatRequest, ChatResponse (kotlinx.serialization)
  platform/            — Clock, KeyValueStorage, Logger, UuidGenerator, DateFormatter, AppDispatchers

androidMain            — Room DAOs + Entity, SharedPrefs, Ktor-OkHttp engine, ViewModel, Koin
jsMain                 — localStorage, Ktor-JS engine, actual-реализации платформенных интерфейсов
```

### Что останется в androidMain / app

| Компонент | Причина |
|---|---|
| Все `*Entity` классы | Room `@Entity`, `@PrimaryKey` — только JVM |
| `AppDatabase` + все DAO | Room — только Android |
| `AgentMemory` (текущая реализация) | `SharedPreferences`, `Context` |
| `UserProfileRepository` (текущая реализация) | `SharedPreferences`, `Context` |
| `AnthropicApi` (Retrofit) | Retrofit — только JVM; заменится на Ktor в фазе 4 |
| `AppModule.kt` | Koin `androidContext()`, `viewModel {}` |
| `AgentViewModel`, `ChatViewModel` | `androidx.lifecycle.ViewModel` |
| Весь `presentation/` слой | Jetpack Compose |
| `MainActivity`, `MyApp` | Android Application |

### Что перейдёт в shared/commonMain

| Компонент | Текущий статус | Фаза |
|---|---|---|
| `domain/model/` | ✅ Pure Kotlin, готов | 3 |
| `domain/repository/` интерфейсы | 🆕 Создать | 1 |
| `domain/usecase/` | ✅ Почти готов | 3 |
| `agent/AgentStep` | ✅ Pure Kotlin, готов | 3 |
| `agent/AgentRunner` | ✅ Нет Android-импортов; принимает `vararg McpProviderFacade`, маршрутизирует через `toolToProvider` map | 3 |
| `agent/LLMAgent` | ⚠️ `SimpleDateFormat`, `UUID`, `System` | 3 (после фаз 1–2) |
| `data/api/model/` DTO | ⚠️ Нужна замена Gson → kotlinx.serialization | 4 |
| `platform/` интерфейсы | 🆕 Создать | 2 |

---

## Прогресс

| Фаза | Статус |
|---|---|
| Фаза 0 — Baseline тесты + MCP рефакторинг | ✅ Завершена (175 тестов, 0 failures) |
| Фаза 1 — Domain слой | 🔲 Не начата |
| Фаза 2 — Platform абстракции | 🔲 Не начата |
| Фаза 3 — Shared KMP модуль | 🔲 Не начата |
| Фаза 4 — Network слой (Ktor) | 🔲 Не начата |
| Фаза 5 — JS таргет / Web клиент | 🔲 Не начата |

---

## Фаза 0 — Baseline тесты + MCP рефакторинг ✅ ЗАВЕРШЕНА

> **Цель:** зафиксировать текущее поведение тестами до начала любых рефакторингов. Тесты становятся safety net — если что-то сломается в фазах 1–5, тесты сразу покажут это.

> **Почему важно делать первым:** без тестов невозможно безопасно рефакторить. Тесты написаны против текущего кода, а не против будущего — они описывают реальное поведение, а не желаемое.

**Результат: 175 тестов, 0 failures, 0 errors. Baseline зафиксирован.**

### Написанные тестовые файлы

| Файл | Тестов | Покрытие |
|---|---|---|
| `AgentRunnerTest.kt` | 16 | ReAct loop, FINAL_ANSWER, STORE/SEARCH_MEMORY, CALCULATE, maxIterations, парсинг, API error |
| `BuildHistoryTest.kt` | 12 | Все 5 стратегий: FULL, SLIDING_WINDOW, STICKY_FACTS (с/без фактов), COMPRESSION (ниже/выше порога, кэш, регенерация), BRANCHING |
| `BuildInstructionsTest.kt` | 11 | systemPrompt, memory, userProfile — по отдельности и вместе; model, temperature passthrough |
| `SendMessageTest.kt` | 11 | Persistence user/assistant msg, title auto-set, token counts, error cases, STICKY_FACTS trigger |
| `BuildBranchHistoryTest.kt` | 9 | Один узел, child→root, цепочка из 3, persistence, first-msg auto-label |
| `AgentMemoryTest.kt` | 10 | store/recall, forget, forgetAll, toContextString (пусто / с данными) |
| `UserProfileRepositoryTest.kt` | 10 | toggle off, profile only, task only, оба включены, trim whitespace |
| `TaskFsmRepositoryTest.kt` | 24 | FSM state transitions, pause/resume, autoRun, error handling |
| `FsmLLMAgentTest.kt` | 14 | FSM интеграция в LLMAgent: ручной/авто режим, обработка ошибок |
| `ConstraintsRepositoryTest.kt` | 8 | toContextBlock, defaults, enabled/disabled |
| `ConstraintsCheckTest.kt` | 13 | pre/post-check нарушений, альтернатива, toInstructionsBlock |
| `LLMAgentTestBase.kt` | — | Инфраструктура: FakeSessionDao, FakeMessageDao, FakeSummaryDao, FakeFactDao, FakeBranchNodeDao, builders |

### Изменения в конфигурации сборки
- `jvmTarget` повышен с `1.8` → `11` (требование mockito-kotlin)
- `libs.versions.toml`: добавлены `mockito-kotlin:5.2.1`, `mockito-core:5.8.0`, `kotlinx-coroutines-test`
- `app/build.gradle.kts`: добавлены `testImplementation` зависимости

### Контрольный список

- [x] **0.1** Тесты `AgentRunner` — `AgentRunnerTest.kt`
- [x] **0.2** Тесты `buildHistory` — все 5 стратегий — `BuildHistoryTest.kt`
- [x] **0.3** Тесты `buildInstructions` — `BuildInstructionsTest.kt`
- [x] **0.4** Тесты `AgentMemory` — `AgentMemoryTest.kt`
- [x] **0.5** Тесты `UserProfileRepository.toContextString()` — `UserProfileRepositoryTest.kt`
- [x] **0.6** Тесты `buildBranchHistory` — `BuildBranchHistoryTest.kt`
- [x] **0.7** Integration тест `sendMessage` с FakeApi + FakeDAO — `SendMessageTest.kt`
- [x] **0.8** Все 86 тестов проходят — **baseline зафиксирован**
- [x] **0.9** Расширение baseline: FSM, Constraints, Branching, Telegram MCP тесты — итого **175 тестов**
- [x] **0.10** Kotlin 1.9.25 → 2.1.0; KSP 2.1.0-1.0.29; добавлены плагины `kotlin.plugin.compose`, `kotlin.plugin.serialization`
- [x] **0.11** Добавлены зависимости: `io.modelcontextprotocol:kotlin-sdk-client:0.9.0`, Ktor 3.2.3 (server artifacts excluded)
- [x] **0.12** Введён `McpProviderFacade` интерфейс; `McpRepository` и `TelegramMcpRepository` реализуют его
- [x] **0.13** `AgentRunner` переведён на `vararg McpProviderFacade` + `toolToProvider` map; убран if/else роутинг
- [x] **0.14** `TelegramMcpClient` обновлён: URL → `http://10.0.2.2:8080/mcp`, убран initialize handshake, методы `internal` (без reflection в тестах)
- [x] **0.15** `network_security_config.xml` — добавлен cleartext для `10.0.2.2`

---

## Фаза 1 — Выделение domain слоя

> **Цель:** разорвать прямую зависимость бизнес-логики (`LLMAgent`) от Room Entity и конкретных DAO. После этой фазы `LLMAgent` работает только с чистыми Kotlin-классами и интерфейсами — без единого `import androidx.room`.

> **Почему важно делать до создания shared модуля:** если перенести `LLMAgent` в shared с зависимостью от `SessionEntity` (который несёт `@Entity`), код не скомпилируется в JS. Разделение entity и domain model — обязательный шаг.

### 1.1 Создать domain-модели

Новые файлы в `domain/model/` — чистые Kotlin data class без аннотаций Room:

```kotlin
// domain/model/Session.kt
data class Session(
    val id: String,
    val startedAt: Long,
    val title: String,
    val systemPrompt: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 1.0f,
    val memoryStrategy: String = MemoryStrategy.FULL.name,
    val compressionEnabled: Boolean = false,
    val compressionN: Int = 5,
    val compressionM: Int = 6,
    val slidingWindowN: Int = 5,
    val stickyFactsN: Int = 5
)

// domain/model/MessageData.kt
data class MessageData(
    val id: String,
    val sessionId: String,
    val content: String,
    val isFromUser: Boolean,
    val createdAt: Long,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val durationMs: Long = 0,
    val model: String = "",
    val branchNodeId: String? = null
)

// domain/model/SummaryData.kt
data class SummaryData(
    val sessionId: String,
    val summary: String,
    val coveredMessageCount: Int,
    val updatedAt: Long
)

// domain/model/FactData.kt
data class FactData(
    val sessionId: String,
    val factKey: String,
    val factValue: String
)

// domain/model/BranchNode.kt
data class BranchNode(
    val id: String,
    val sessionId: String,
    val parentId: String?,
    val label: String,
    val createdAt: Long
)
```

### Примечание по рефакторингу (выполнен до Фазы 1)

До начала Фазы 1 был проведён рефакторинг проекта:

- **`ChatResponse.extractText()`** — extension-функция, единственное место извлечения текста из API-ответа. Заменила 6 дублирующих цепочек `.output.firstOrNull { it.type == "message" }?.content?.firstOrNull { it.type == "output_text" }?.text` в `LLMAgent`, `ChatRepositoryImpl`, `AgentRunner`.
- **`SessionContextConfig`** — data class для 9 полей настроек сессии. Заменила 10-параметрический `updateSessionContext()` и 9-параметрическую лямбду `onSave` в `ContextSettingsSheet`.
- **`askConstraintsChecker()`** — приватный helper, устранил дублирование между `checkConstraintViolation` и `checkResponseViolation`.
- **`buildUserInfoLines()`** — приватный helper в `UserProfileRepository`, устранил дублирование между `userInformationContextString()` и `toContextString()`.
- **`sessionJobs: Map<String, Job>`** в `AgentViewModel` — заменил 4 отдельных Job-поля.

При выполнении Фазы 1 учесть:
- `SessionContextConfig` — кандидат для переноса в `domain/` (чистый Kotlin data class без аннотаций).
- `extractText()` переедет вместе с `ChatResponse` в `shared/commonMain/data/api/model/`.

### 1.2 Создать интерфейсы репозиториев

Новые файлы в `domain/repository/`:

```kotlin
// SessionRepository.kt
interface SessionRepository {
    fun observeAll(): Flow<List<Session>>
    suspend fun getLatest(): Session?
    suspend fun getById(id: String): Session?
    suspend fun insert(session: Session)
    suspend fun updateContext(id: String, config: SessionContextConfig)  // data class, не 9 параметров
    suspend fun updateTitle(id: String, title: String)
    suspend fun countAssistantMessages(sessionId: String): Int
}

// MessageRepository.kt
interface MessageRepository {
    suspend fun insert(message: MessageData)
    fun observeBySession(sessionId: String): Flow<List<MessageData>>
    suspend fun getByNode(nodeId: String): List<MessageData>
    fun observeByNode(nodeId: String): Flow<List<MessageData>>
}

// SummaryRepository.kt
interface SummaryRepository {
    suspend fun getBySession(sessionId: String): SummaryData?
    suspend fun upsert(summary: SummaryData)
    fun observeBySession(sessionId: String): Flow<SummaryData?>
}

// FactRepository.kt
interface FactRepository {
    suspend fun getBySession(sessionId: String): List<FactData>
    suspend fun deleteBySession(sessionId: String)
    suspend fun upsertAll(facts: List<FactData>)
}

// BranchNodeRepository.kt
interface BranchNodeRepository {
    suspend fun getBySession(sessionId: String): List<BranchNode>
    fun observeBySession(sessionId: String): Flow<List<BranchNode>>
    suspend fun insert(node: BranchNode)
    suspend fun getById(id: String): BranchNode?
    suspend fun updateLabel(id: String, label: String)
}
```

### 1.3 Создать Room-реализации интерфейсов

Новые файлы в `data/repository/room/` — каждый содержит маппинг Entity ↔ domain model:

```kotlin
// RoomSessionRepository.kt
class RoomSessionRepository(private val dao: SessionDao) : SessionRepository {
    override fun observeAll() = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun getById(id: String) = dao.getById(id)?.toDomain()
    // ... остальные методы делегируют в dao с маппингом
}

// Функции расширения для маппинга:
fun SessionEntity.toDomain(): Session = Session(id, startedAt, title, ...)
fun Session.toEntity(): SessionEntity = SessionEntity(id, startedAt, title, ...)
```

Аналогично для `RoomMessageRepository`, `RoomSummaryRepository`, `RoomFactRepository`, `RoomBranchNodeRepository`.

### 1.4 Переписать LLMAgent

Заменить все зависимости от DAO и Entity на интерфейсы и domain-модели:

```kotlin
// До:
class LLMAgent(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    ...
)

// После:
class LLMAgent(
    private val sessionRepo: SessionRepository,
    private val messageRepo: MessageRepository,
    private val summaryRepo: SummaryRepository,
    private val factRepo: FactRepository,
    private val branchNodeRepo: BranchNodeRepository,
    ...
)
```

Внутри `LLMAgent` заменить:
- `SessionEntity` → `Session`
- `MessageEntity` → `MessageData`
- `SummaryEntity` → `SummaryData`
- `FactEntity` → `FactData`
- `BranchNodeEntity` → `BranchNode`
- Все вызовы DAO → вызовы репозиториев

### 1.5 Обновить AppModule.kt

```kotlin
// Зарегистрировать реализации репозиториев:
single<SessionRepository> { RoomSessionRepository(get()) }
single<MessageRepository> { RoomMessageRepository(get()) }
single<SummaryRepository> { RoomSummaryRepository(get()) }
single<FactRepository> { RoomFactRepository(get()) }
single<BranchNodeRepository> { RoomBranchNodeRepository(get()) }

// LLMAgent теперь принимает репозитории:
single { LLMAgent(get(), get(), get(), get(), get(), get(), get()) }
```

### 1.6 Обновить тестовую инфраструктуру

`LLMAgentTestBase.kt` уже содержит Fake-реализации DAO (`FakeSessionDao` и др.). После рефакторинга они становятся реализациями новых интерфейсов репозиториев. Переименовать и привести к новым сигнатурам.

### Контрольный список

- [ ] **1.1** Создать `Session`, `MessageData`, `SummaryData`, `FactData`, `BranchNode` в `domain/model/`; перенести туда же `SessionContextConfig` из `data/db/entity/`
- [ ] **1.2** Создать 5 интерфейсов репозиториев в `domain/repository/`
- [ ] **1.3** Создать Room-реализации в `data/repository/room/` с маппингом Entity ↔ domain
- [ ] **1.4** Переписать `LLMAgent` — убрать все DAO и Entity, работать только через интерфейсы
- [ ] **1.5** Обновить `AppModule.kt` — внедрять репозитории вместо DAO
- [ ] **1.6** Обновить `LLMAgentTestBase.kt` под новые интерфейсы
- [ ] **1.7** `./gradlew :app:testDebugUnitTest` — все 175 тестов проходят

---

## Фаза 2 — Абстрагирование платформенных зависимостей

> **Цель:** убрать из бизнес-логики все классы, которые не существуют в Kotlin/JS: `java.util.UUID`, `java.text.SimpleDateFormat`, `System.currentTimeMillis()`, `android.util.Log`, `android.content.Context`, `SharedPreferences`, `Dispatchers.IO`. После этой фазы `LLMAgent`, `AgentMemory`, `AgentRunner` не содержат ни одного `import java.*` или `import android.*`.

> **Почему важно делать до создания shared модуля:** компилятор Kotlin/JS выбросит ошибку на каждом таком импорте. Лучше убрать их сейчас в знакомом Android-контексте, чем разбираться с ошибками компиляции уже в multiplatform модуле.

### 2.1 Создать интерфейсы платформенных зависимостей

Новый пакет `platform/` в `app/src/main/`:

```kotlin
// platform/Clock.kt
interface Clock {
    fun nowMillis(): Long
}

// platform/UuidGenerator.kt
interface UuidGenerator {
    fun generate(): String
}

// platform/DateFormatter.kt
interface DateFormatter {
    fun formatSessionTitle(millis: Long): String  // "dd MMM yyyy, HH:mm"
}

// platform/Logger.kt
interface Logger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

// platform/AppDispatchers.kt
interface AppDispatchers {
    val io: CoroutineDispatcher
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
}

// platform/KeyValueStorage.kt
interface KeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getAll(): Map<String, String>
    fun remove(key: String)
    fun clear()
}
```

### 2.2 Создать Android-реализации

Новый пакет `platform/android/`:

```kotlin
// AndroidClock.kt
class AndroidClock : Clock {
    override fun nowMillis() = System.currentTimeMillis()
}

// AndroidUuidGenerator.kt
class AndroidUuidGenerator : UuidGenerator {
    override fun generate() = java.util.UUID.randomUUID().toString()
}

// AndroidDateFormatter.kt — использует kotlinx-datetime (см. 2.3)
class AndroidDateFormatter : DateFormatter {
    override fun formatSessionTitle(millis: Long): String {
        val instant = Instant.fromEpochMilliseconds(millis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "%02d %s %d, %02d:%02d".format(
            local.dayOfMonth,
            local.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
            local.year, local.hour, local.minute
        )
    }
}

// AndroidLogger.kt
class AndroidLogger : Logger {
    override fun d(tag: String, message: String) = android.util.Log.d(tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?) =
        android.util.Log.e(tag, message, throwable)
}

// AndroidDispatchers.kt
class AndroidDispatchers : AppDispatchers {
    override val io = Dispatchers.IO
    override val main = Dispatchers.Main
    override val default = Dispatchers.Default
}

// SharedPrefsKeyValueStorage.kt
class SharedPrefsKeyValueStorage(context: Context, name: String) : KeyValueStorage {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String) = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    override fun getAll() = prefs.all.mapValues { it.value.toString() }
    override fun remove(key: String) = prefs.edit { remove(key) }
    override fun clear() = prefs.edit { clear() }
}
```

### 2.3 Добавить kotlinx-datetime

```toml
# libs.versions.toml
datetime = "0.6.1"
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.kotlinx.datetime)
```

Заменить `SimpleDateFormat` в `LLMAgent:41` на `DateFormatter` интерфейс.

### 2.4 Применить интерфейсы в существующих классах

**`LLMAgent`** — заменить 4 платформенных вызова:
```kotlin
// До:
private val titleFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
val now = System.currentTimeMillis()
id = UUID.randomUUID().toString()

// После (интерфейсы приходят через конструктор):
class LLMAgent(
    ...,
    private val clock: Clock,
    private val uuidGenerator: UuidGenerator,
    private val dateFormatter: DateFormatter
) {
    val now = clock.nowMillis()
    id = uuidGenerator.generate()
    title = dateFormatter.formatSessionTitle(now)
}
```

**`AgentMemory`** — убрать `Context`, принимать `KeyValueStorage`:
```kotlin
// До:
class AgentMemory(context: Context) {
    private val prefs = context.getSharedPreferences("agent_memory", Context.MODE_PRIVATE)
}

// После:
class AgentMemory(private val storage: KeyValueStorage) {
    fun store(key: String, value: String) = storage.putString(key, value)
    fun recall(key: String) = storage.getString(key)
    fun recallAll() = storage.getAll().map { (k, v) -> MemoryEntry(k, v) }
    fun forget(key: String) = storage.remove(key)
    fun forgetAll() = storage.clear()
    fun toContextString(): String { ... }
}
```

**`UserProfileRepository`** — убрать `Context`, принимать `KeyValueStorage`:
```kotlin
class UserProfileRepository(private val storage: KeyValueStorage) {
    var profileDescription: String
        get() = storage.getString(KEY_PROFILE_DESCRIPTION) ?: ""
        set(value) = storage.putString(KEY_PROFILE_DESCRIPTION, value)
    // ... аналогично для остальных полей
}
```

### 2.5 Создать LLMApiClient интерфейс

```kotlin
// domain/api/LLMApiClient.kt
interface LLMApiClient {
    suspend fun sendMessage(request: ChatRequest): ChatResponse
    suspend fun getModels(): ModelsResponse
}
```

Существующий `AnthropicApi` (Retrofit) реализует этот интерфейс — можно добавить `implements LLMApiClient` без изменения кода. `LLMAgent` переключить на зависимость от `LLMApiClient` вместо `AnthropicApi`.

### 2.6 Обновить AppModule.kt

```kotlin
// Платформенные реализации:
single<Clock> { AndroidClock() }
single<UuidGenerator> { AndroidUuidGenerator() }
single<DateFormatter> { AndroidDateFormatter() }
single<Logger> { AndroidLogger() }
single<AppDispatchers> { AndroidDispatchers() }
single<KeyValueStorage>(named("memory")) { SharedPrefsKeyValueStorage(androidContext(), "agent_memory") }
single<KeyValueStorage>(named("profile")) { SharedPrefsKeyValueStorage(androidContext(), "user_profile") }

// Переписанные классы:
single { AgentMemory(get(named("memory"))) }
single { UserProfileRepository(get(named("profile"))) }
single { LLMAgent(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
```

### 2.7 Обновить тесты

Теперь `AgentMemory` и `UserProfileRepository` принимают `KeyValueStorage` — убрать Mockito-моки, использовать `FakeKeyValueStorage` (in-memory map) напрямую. Тесты становятся чище:

```kotlin
// Вместо:
val memory = mock<AgentMemory>()
whenever(memory.toContextString()).thenReturn("")

// После:
val storage = FakeKeyValueStorage()
val memory = AgentMemory(storage)
```

### Контрольный список

- [ ] **2.1** Создать 6 интерфейсов в `platform/`: `Clock`, `UuidGenerator`, `DateFormatter`, `Logger`, `AppDispatchers`, `KeyValueStorage`
- [ ] **2.2** Создать Android-реализации в `platform/android/`
- [ ] **2.3** Добавить `kotlinx-datetime` в зависимости, реализовать `AndroidDateFormatter`
- [ ] **2.4.1** Переписать `LLMAgent`: внедрить `Clock`, `UuidGenerator`, `DateFormatter` через конструктор, убрать `SimpleDateFormat`, `UUID`, `System`
- [ ] **2.4.2** Переписать `AgentMemory`: убрать `Context`, принимать `KeyValueStorage`
- [ ] **2.4.3** Переписать `UserProfileRepository`: убрать `Context`, принимать `KeyValueStorage`
- [ ] **2.5** Создать `LLMApiClient` интерфейс, переключить `LLMAgent` на него
- [ ] **2.6** Обновить `AppModule.kt`
- [ ] **2.7** Обновить тесты: убрать Mockito-моки `AgentMemory`, использовать `FakeKeyValueStorage`
- [ ] **2.8** `./gradlew :app:testDebugUnitTest` — все 175 тестов проходят
- [ ] **2.9** `./gradlew :app:assembleDebug` — приложение собирается и работает

---

## Фаза 3 — Создание shared KMP модуля

> **Цель:** физически создать `:shared` модуль и перенести в него весь код, который прошёл через фазы 1 и 2 — теперь он чистый Kotlin без платформенных зависимостей.

> **Предусловие:** фазы 1 и 2 полностью завершены. `LLMAgent` не содержит `import java.*` или `import android.*`. Все тесты проходят.

### 3.1 Создать структуру модуля

```
shared/
├── build.gradle.kts
└── src/
    ├── commonMain/kotlin/com/example/myapplication/
    │   ├── domain/model/
    │   ├── domain/repository/
    │   ├── domain/usecase/
    │   ├── agent/
    │   ├── data/api/model/
    │   └── platform/           ← интерфейсы (без реализаций)
    ├── commonTest/kotlin/
    │   └── ...                 ← перенесённые тесты из app/src/test
    ├── androidMain/kotlin/
    │   └── platform/android/   ← actual-реализации для Android
    └── jsMain/kotlin/
        └── platform/js/        ← actual-реализации для Web
```

Добавить в `settings.gradle.kts`:
```kotlin
include(":app", ":shared")
```

### 3.2 Настроить shared/build.gradle.kts

```kotlin
plugins {
    kotlin("multiplatform") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions { jvmTarget = "11" } }
    }
    js(IR) {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("io.ktor:ktor-client-core:3.2.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.2.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.2.3")
        }
        jsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.2.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
        }
    }
}

android {
    namespace = "com.example.myapplication.shared"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
}
```

### 3.3 Подключить shared к app

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":shared"))
}
```

### 3.4 Перенести код в commonMain

Порядок переноса — от наименее связанных к наиболее:

1. `domain/model/` — `Session`, `Message`, `MessageMeta`, `MessageData`, `SummaryData`, `FactData`, `BranchNode`, `MemoryStrategy`
2. `domain/repository/` — все 5 интерфейсов
3. `domain/usecase/SendMessageUseCase`
4. `agent/AgentStep`, `agent/AgentStepType`
5. `agent/AgentRunner`
6. `platform/` — все 6 интерфейсов
7. `data/api/model/` — `ChatRequest`, `ChatResponse`, `InputMessage`, `OutputItem`, `OutputContent`, `UsageInfo` (с `@Serializable` вместо Gson)
8. `agent/LLMAgent` — последним, когда всё вышеперечисленное уже в shared

### 3.5 Создать expect/actual для платформенных функций

Для `AgentStep.id` (использует `java.util.UUID`):

```kotlin
// commonMain
expect fun generateUuid(): String

// androidMain
actual fun generateUuid(): String = java.util.UUID.randomUUID().toString()

// jsMain
actual fun generateUuid(): String = js("crypto.randomUUID()") as String
```

### 3.6 Создать androidMain реализации платформенных интерфейсов

Перенести `platform/android/` из модуля `app` в `shared/androidMain/`. Теперь это `actual`-реализации:

- `AndroidClock`, `AndroidUuidGenerator`, `AndroidDateFormatter`
- `AndroidLogger`, `AndroidDispatchers`
- `SharedPrefsKeyValueStorage`

### 3.7 Создать jsMain реализации платформенных интерфейсов

```kotlin
// jsMain/platform/js/JsClock.kt
class JsClock : Clock {
    override fun nowMillis() = Date().getTime().toLong()
}

// jsMain/platform/js/JsUuidGenerator.kt
class JsUuidGenerator : UuidGenerator {
    override fun generate(): String = js("crypto.randomUUID()") as String
}

// jsMain/platform/js/JsLogger.kt
class JsLogger : Logger {
    override fun d(tag: String, message: String) = console.log("[$tag] $message")
    override fun e(tag: String, message: String, throwable: Throwable?) =
        console.error("[$tag] $message: ${throwable?.message}")
}

// jsMain/platform/js/JsDispatchers.kt
class JsDispatchers : AppDispatchers {
    override val io = Dispatchers.Default      // нет IO в JS
    override val main = Dispatchers.Main
    override val default = Dispatchers.Default
}

// jsMain/platform/js/JsKeyValueStorage.kt — будет доработана в фазе 5
```

### 3.8 Перенести тесты в commonTest

Все тесты из `app/src/test/` кроме Android-специфичных переносятся в `shared/commonTest/`. Тесты уже написаны с Fake-реализациями без Android — они компилируются в commonTest без изменений. Mockito заменить на ручные Fake-классы (Mockito не поддерживается в commonTest).

Останется в `app/src/test/` (platform-specific):
- Любые тесты с `@RunWith(AndroidJUnit4)` — но таких пока нет

### 3.9 Валидация

```bash
./gradlew :shared:compileKotlinAndroid   # должно быть успешно
./gradlew :shared:compileKotlinJs        # должно быть успешно (после фазы 5)
./gradlew :shared:allTests               # все commonTest проходят
./gradlew :app:assembleDebug             # Android APK собирается
```

### Контрольный список

- [ ] **3.1** Создать `shared/` директорию и `shared/build.gradle.kts`
- [ ] **3.2** Добавить `include(":shared")` в `settings.gradle.kts`
- [ ] **3.3** Добавить `implementation(project(":shared"))` в `app/build.gradle.kts`
- [ ] **3.4.1** Перенести `domain/model/` в `shared/commonMain`
- [ ] **3.4.2** Перенести `domain/repository/` интерфейсы в `shared/commonMain`
- [ ] **3.4.3** Перенести `domain/usecase/` в `shared/commonMain`
- [ ] **3.4.4** Перенести `agent/AgentStep` в `shared/commonMain`
- [ ] **3.4.5** Перенести `agent/AgentRunner` в `shared/commonMain`
- [ ] **3.4.6** Перенести `platform/` интерфейсы в `shared/commonMain`
- [ ] **3.4.7** Перенести `agent/LLMAgent` в `shared/commonMain`
- [ ] **3.5** Создать `expect fun generateUuid()` с `actual` для android и js
- [ ] **3.6** Перенести `platform/android/` реализации в `shared/androidMain`
- [ ] **3.7** Создать `jsMain` заглушки платформенных реализаций
- [ ] **3.8** Перенести тесты в `shared/commonTest`, убрать Mockito (заменить на Fake-классы)
- [ ] **3.9** `./gradlew :shared:compileKotlinAndroid` — успешно
- [ ] **3.10** `./gradlew :shared:allTests` — все тесты проходят
- [ ] **3.11** `./gradlew :app:assembleDebug` — приложение собирается и работает на устройстве

---

## Фаза 4 — Замена network слоя (Retrofit → Ktor)

> **Цель:** убрать Retrofit и Gson из проекта полностью. Заменить на Ktor (поддерживает KMP) и `kotlinx.serialization` (работает в JS). После этой фазы модуль `app` не содержит `retrofit2` и `gson` зависимостей.

> **Почему Retrofit нельзя использовать в shared:** Retrofit использует `java.lang.reflect` и runtime-аннотации — это JVM-специфика, которой нет в Kotlin/JS. Ktor — единственный HTTP-клиент с полноценной KMP поддержкой.

### 4.1 Заменить Gson-аннотации на kotlinx.serialization

Во всех DTO в `data/api/model/` (`ChatRequest`, `ChatResponse`, `InputMessage`, `OutputItem`, `OutputContent`, `UsageInfo`, `ModelsResponse`):

```kotlin
// До (Gson):
data class ChatRequest(
    val model: String,
    @SerializedName("max_output_tokens") val maxOutputTokens: Int? = null,
    val input: List<InputMessage>,
    val instructions: String? = null,
    val temperature: Float? = null
)

// После (kotlinx.serialization):
@Serializable
data class ChatRequest(
    val model: String,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val input: List<InputMessage>,
    val instructions: String? = null,
    val temperature: Float? = null
)
```

Поля API использующие `snake_case` (например `input_tokens`, `output_tokens` в `UsageInfo`) требуют явного `@SerialName`. Проверить все поля.

### 4.2 Реализовать KtorLLMApiClient в shared/commonMain

```kotlin
// shared/commonMain/data/api/KtorLLMApiClient.kt
class KtorLLMApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String
) : LLMApiClient {

    override suspend fun sendMessage(request: ChatRequest): ChatResponse =
        httpClient.post("$baseUrl/responses") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun getModels(): ModelsResponse =
        httpClient.get("$baseUrl/models") {
            header("Authorization", "Bearer $apiKey")
        }.body()
}
```

### 4.3 Создать HttpClient для каждой платформы

```kotlin
// androidMain: KtorHttpClientProvider.kt
fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    engine {
        config {
            connectTimeout(120, TimeUnit.SECONDS)
            readTimeout(120, TimeUnit.SECONDS)
            writeTimeout(120, TimeUnit.SECONDS)
        }
    }
}

// jsMain: KtorHttpClientProvider.kt
fun createHttpClient(): HttpClient = HttpClient(Js) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
```

### 4.4 Обновить AppModule.kt

```kotlin
// Заменить Retrofit + OkHttp на Ktor:
single { createHttpClient() }  // androidMain реализация
single<LLMApiClient> {
    KtorLLMApiClient(
        httpClient = get(),
        baseUrl = "https://api.proxyapi.ru/openai/v1/",
        apiKey = "sk-..."
    )
}

// Удалить:
// single { OkHttpClient.Builder()... }
// single { Retrofit.Builder()... }
// single<AnthropicApi> { get<Retrofit>().create(...) }
```

### 4.5 Удалить Retrofit и Gson

Из `app/build.gradle.kts` удалить:
```kotlin
// implementation(libs.retrofit)
// implementation(libs.retrofit.converter.gson)
// implementation(libs.okhttp.logging.interceptor)
```

Из `libs.versions.toml` удалить секции `retrofit` и `okhttp` (или оставить okhttp для Ktor engine).

Проверить что нигде в коде не осталось `import retrofit2.*` или `import com.google.gson.*`.

### 4.6 Добавить логирование в Ktor

Заменить `HttpLoggingInterceptor` (OkHttp) на Ktor logging plugin:
```kotlin
install(Logging) {
    level = LogLevel.BODY
}
```

### Контрольный список

- [ ] **4.1** Добавить `@Serializable` и `@SerialName` ко всем DTO в `data/api/model/`
- [ ] **4.2** Реализовать `KtorLLMApiClient` в `shared/commonMain/data/api/`
- [ ] **4.3.1** Создать `createHttpClient()` в `shared/androidMain` (OkHttp engine)
- [ ] **4.3.2** Создать `createHttpClient()` в `shared/jsMain` (Js engine)
- [ ] **4.4** Обновить `AppModule.kt` — заменить Retrofit на Ktor
- [ ] **4.5** Удалить Retrofit и Gson из `app/build.gradle.kts`
- [ ] **4.6** Добавить Ktor Logging plugin
- [ ] **4.7** `./gradlew :app:testDebugUnitTest` — все тесты проходят
- [ ] **4.8** Ручная проверка: отправить сообщение на реальном устройстве, убедиться что API отвечает

---

## Фаза 5 — JS таргет и подготовка Web клиента

> **Цель:** убедиться что `shared` модуль успешно компилируется в JavaScript и готов к использованию в Web клиенте. Реализовать все jsMain-заглушки, написанные в фазе 3. Создать скелет Web-модуля.

> **Предусловие:** фаза 4 завершена. `./gradlew :shared:compileKotlinAndroid` проходит. Все DTO используют `kotlinx.serialization`.

### 5.1 Завершить jsMain реализации платформенных интерфейсов

```kotlin
// jsMain/platform/js/JsKeyValueStorage.kt
class JsKeyValueStorage(private val prefix: String = "app_") : KeyValueStorage {
    override fun getString(key: String): String? = localStorage.getItem("$prefix$key")
    override fun putString(key: String, value: String) = localStorage.setItem("$prefix$key", value)
    override fun getBoolean(key: String, default: Boolean): Boolean =
        localStorage.getItem("$prefix$key")?.toBoolean() ?: default
    override fun putBoolean(key: String, value: Boolean) =
        localStorage.setItem("$prefix$key", value.toString())
    override fun getAll(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (i in 0 until localStorage.length) {
            val fullKey = localStorage.key(i) ?: continue
            if (fullKey.startsWith(prefix)) {
                result[fullKey.removePrefix(prefix)] = localStorage.getItem(fullKey) ?: continue
            }
        }
        return result
    }
    override fun remove(key: String) = localStorage.removeItem("$prefix$key")
    override fun clear() {
        val keysToRemove = (0 until localStorage.length)
            .mapNotNull { localStorage.key(it) }
            .filter { it.startsWith(prefix) }
        keysToRemove.forEach { localStorage.removeItem(it) }
    }
}

// jsMain/platform/js/JsDateFormatter.kt
class JsDateFormatter : DateFormatter {
    override fun formatSessionTitle(millis: Long): String {
        val instant = Instant.fromEpochMilliseconds(millis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "%02d %s %d, %02d:%02d".format(...)
    }
}
```

### 5.2 Создать DI для JS таргета

Koin поддерживает KMP — создать JS-аналог `AppModule`:
```kotlin
// jsMain/di/JsModule.kt
val jsModule = module {
    single<Clock> { JsClock() }
    single<UuidGenerator> { JsUuidGenerator() }
    single<DateFormatter> { JsDateFormatter() }
    single<Logger> { JsLogger() }
    single<AppDispatchers> { JsDispatchers() }
    single<KeyValueStorage>(named("memory")) { JsKeyValueStorage("memory_") }
    single<KeyValueStorage>(named("profile")) { JsKeyValueStorage("profile_") }
    single<LLMApiClient> { KtorLLMApiClient(get(), BASE_URL, API_KEY) }
    single { createHttpClient() }
    // Репозитории — нужна JS-реализация persistence (IndexedDB или localStorage)
}
```

### 5.3 Проверить компиляцию

```bash
./gradlew :shared:compileKotlinJs        # должно быть успешно
./gradlew :shared:jsTest                 # commonTest тесты в JS runtime
./gradlew :shared:allTests               # Android + JS тесты
```

Типичные ошибки которые могут возникнуть:
- Забытый `import java.*` в commonMain → переместить в `expect/actual`
- `Dispatchers.IO` в commonMain → заменить на `AppDispatchers.io`
- `runBlocking` в тестах → заменить на `runTest`

### 5.4 Реализовать JS persistence (IndexedDB / localStorage)

Репозитории в JS не могут использовать Room. Варианты:
- **localStorage** — простой, синхронный, лимит ~5 МБ
- **IndexedDB** — async, без лимита, сложнее в реализации
- **SQLDelight** — KMP-совместимая альтернатива Room (рекомендуется для долгосрочного решения)

Для MVP Web клиента достаточно localStorage.

### 5.5 Создать модуль webClient

```
webClient/
├── build.gradle.kts        — kotlin("js") + зависимость на :shared
├── src/main/
│   ├── kotlin/             — Compose for Web или React wrapper
│   └── resources/
│       └── index.html
```

### 5.6 Задокументировать публичный API shared модуля

Создать `shared/API.md`:
- Какие классы являются публичным API
- Как инициализировать (DI)
- Как создать сессию и отправить сообщение
- Какие интерфейсы нужно реализовать для новой платформы

### Контрольный список

- [ ] **5.1** Реализовать `JsKeyValueStorage`, `JsDateFormatter` в `shared/jsMain`
- [ ] **5.2** Создать `jsModule` (Koin) для JS таргета
- [ ] **5.3** `./gradlew :shared:compileKotlinJs` — успешно
- [ ] **5.4** `./gradlew :shared:jsTest` — все commonTest проходят в JS runtime
- [ ] **5.5** Выбрать стратегию persistence для Web (localStorage / IndexedDB / SQLDelight)
- [ ] **5.6** Реализовать JS-репозитории (минимум `SessionRepository` и `MessageRepository`)
- [ ] **5.7** Создать заготовку модуля `:webClient`
- [ ] **5.8** Убедиться что базовый flow (создать сессию → отправить сообщение → получить ответ) работает в браузере
- [ ] **5.9** Задокументировать публичный API в `shared/API.md`

---

## Риски и как их избежать

| Риск | Последствие | Решение |
|---|---|---|
| Перенести `SessionEntity` с `@Entity` в shared до фазы 1 | Не компилируется в JS — `@Entity` это Room-аннотация | Сначала создать domain model `Session` (фаза 1), перенести в shared уже чистый класс |
| Перенести `LLMAgent` в shared до фазы 2 | Ошибки компиляции на `SimpleDateFormat`, `UUID`, `System` | Сначала заменить всё на интерфейсы (фаза 2), потом переносить |
| `Dispatchers.IO` в commonMain | `UnsupportedOperationException` в JS | Использовать `AppDispatchers.io` везде; `actual`-реализация для JS вернёт `Dispatchers.Default` |
| Gson `@SerializedName` в shared | Gson не работает в JS, kotlinx.serialization не читает Gson-аннотации | Фаза 4: заменить `@SerializedName` → `@SerialName` до переноса DTO в shared |
| `runBlocking` в тестах commonTest | `runBlocking` не поддерживается в JS | Использовать `runTest` из `kotlinx-coroutines-test` везде |
| Зависимость shared → app | Циклическая зависимость, сборка сломается | shared никогда не импортирует ничего из `:app` |
| API ключ в shared коде | Ключ попадёт в JS bundle (клиентский код) | Ключ хранить только в androidMain/jsMain конфигурациях, не в commonMain |
| Mockito в commonTest | Mockito — JVM-only, не компилируется в JS | Заменить все моки на ручные Fake-классы в commonTest (фаза 3.8) |
| Разные `CoroutineDispatcher` поведения | JS однопоточный: `IO` == `Default`; тесты могут вести себя иначе | Писать тесты через `AppDispatchers`, не через `Dispatchers.IO` напрямую |

---

## CI/CD (добавить после фазы 3)

```yaml
- name: Compile shared for Android
  run: ./gradlew :shared:compileKotlinAndroid

- name: Compile shared for JS
  run: ./gradlew :shared:compileKotlinJs

- name: Run shared commonTest
  run: ./gradlew :shared:allTests

- name: Run Android unit tests
  run: ./gradlew :app:testDebugUnitTest

- name: Build Android APK
  run: ./gradlew :app:assembleDebug
```
