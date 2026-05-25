я # Shared Module — Public API

Модуль `:shared` содержит бизнес-логику приложения, общую для Android и Web платформ.

---

## Публичный API

### Domain модели (`domain/model/`)

| Класс | Описание |
|---|---|
| `Session` | Сессия чата (id, title, модель, стратегия памяти, системный промпт) |
| `MessageData` | Сообщение (id, sessionId, content, isFromUser, токены, время) |
| `SummaryData` | Сжатая история (sessionId, summary, кол-во покрытых сообщений) |
| `FactData` | Факт из памяти (sessionId, ключ, значение) |
| `BranchNode` | Узел ветвления диалога (id, sessionId, parentId, label) |
| `MemoryStrategy` | Enum: `FULL`, `SLIDING_WINDOW`, `STICKY_FACTS`, `COMPRESSION`, `BRANCHING` |
| `TaskStage` | Enum: `PLANNING`, `EXECUTION`, `VALIDATION`, `DONE`, `ERROR` |

### Интерфейсы репозиториев (`domain/repository/`)

Все репозитории — интерфейсы. Реализации предоставляются платформой:

| Интерфейс | Методы |
|---|---|
| `SessionRepository` | `observeAll()`, `getById()`, `insert()`, `updateContext()`, `updateTitle()` |
| `MessageRepository` | `insert()`, `observeBySession()`, `getByNode()`, `observeByNode()` |
| `SummaryRepository` | `getBySession()`, `upsert()`, `observeBySession()` |
| `FactRepository` | `getBySession()`, `deleteBySession()`, `upsertAll()` |
| `BranchNodeRepository` | `getBySession()`, `insert()`, `getById()`, `updateLabel()` |
| `TaskFsmRepository` | `getOrCreate()`, `update()`, `observe()` |

### Агент (`agent/`)

| Класс | Описание |
|---|---|
| `LLMAgent` | Основной агент. Принимает репозитории + платформенные интерфейсы через конструктор |
| `AgentRunner` | ReAct-loop агент (до 6 итераций). Принимает `vararg McpProviderFacade` |
| `AgentStep` | Шаг агента: `THOUGHT`, `ACTION`, `OBSERVATION`, `FINAL_ANSWER`, `MEMORY_STORE`, `MEMORY_RECALL` |

### Network (`data/api/`)

| Класс | Описание |
|---|---|
| `LLMApiClient` | Интерфейс HTTP-клиента: `sendMessage(ChatRequest): ChatResponse`, `getModels(): ModelsResponse` |
| `KtorLLMApiClient` | Реализация через Ktor. Принимает `HttpClient`, `baseUrl`, `apiKey` |

Для создания `HttpClient` используй платформенную функцию `createHttpClient()`:
- `androidMain` — OkHttp engine (120s timeout)
- `jsMain` — Js engine

### Платформенные интерфейсы (`platform/`)

Для каждой платформы нужно предоставить реализации:

| Интерфейс | Android | JS |
|---|---|---|
| `Clock` | `AndroidClock` | `JsClock` |
| `UuidGenerator` | `AndroidUuidGenerator` | `JsUuidGenerator` |
| `DateFormatter` | `AndroidDateFormatter` | `JsDateFormatter` |
| `Logger` | `AndroidLogger` | `JsLogger` |
| `AppDispatchers` | `AndroidDispatchers` | `JsDispatchers` |
| `KeyValueStorage` | `SharedPrefsKeyValueStorage` | `JsKeyValueStorage` |

### MCP (`data/mcp/`)

| Класс / Интерфейс | Описание |
|---|---|
| `McpProviderFacade` | Единый интерфейс MCP-провайдера: `connect()`, `callTool()`, `isEnabled`, `isConnected` |
| `McpTool` | Описание инструмента: `name`, `description`, `inputSchemaJson: String` |
| `McpConnectionStatus` | `Disconnected`, `Connecting`, `Connected(tools)`, `Error(message)` |

---

## Инициализация

### Android

```kotlin
// AppModule.kt (Koin)
val appModule = module {
    single { createHttpClient() }                         // androidMain
    single<LLMApiClient> { KtorLLMApiClient(get(), BASE_URL, API_KEY) }
    single<Clock> { AndroidClock() }
    single<UuidGenerator> { AndroidUuidGenerator() }
    single<DateFormatter> { AndroidDateFormatter() }
    single<Logger> { AndroidLogger() }
    single<AppDispatchers> { AndroidDispatchers() }
    single<KeyValueStorage>(named("memory")) { SharedPrefsKeyValueStorage(androidContext(), "agent_memory") }
    single<KeyValueStorage>(named("profile")) { SharedPrefsKeyValueStorage(androidContext(), "user_profile") }
    single<KeyValueStorage>(named("constraints")) { SharedPrefsKeyValueStorage(androidContext(), "constraints") }
    // ... Room-репозитории
    single { LLMAgent(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
```

### Web (JS)

```kotlin
// jsMain/di/JsModule.kt
val jsModule = module {
    single { createHttpClient() }                         // jsMain
    single<LLMApiClient> { KtorLLMApiClient(get(), BASE_URL, API_KEY) }
    single<Clock> { JsClock() }
    single<UuidGenerator> { JsUuidGenerator() }
    single<DateFormatter> { JsDateFormatter() }
    single<Logger> { JsLogger() }
    single<AppDispatchers> { JsDispatchers() }
    single<KeyValueStorage>(named("memory")) { JsKeyValueStorage("memory_") }
    single<KeyValueStorage>(named("profile")) { JsKeyValueStorage("profile_") }
    single<KeyValueStorage>(named("constraints")) { JsKeyValueStorage("constraints_") }
    // ... JS-репозитории (TODO: реализовать через localStorage)
}

// Инициализация в main():
startKoin { modules(jsModule) }
```

---

## Добавление новой платформы

1. Реализовать 6 интерфейсов из `platform/`: `Clock`, `UuidGenerator`, `DateFormatter`, `Logger`, `AppDispatchers`, `KeyValueStorage`
2. Реализовать 5 репозиториев из `domain/repository/` + `TaskFsmRepository`
3. Создать Koin-модуль и вызвать `startKoin { modules(yourModule) }`
4. Создать `HttpClient` через `KtorHttpClientProvider` (или реализовать свой engine)

---

## Типичное использование

```kotlin
// Отправить сообщение в сессии
val agent = get<LLMAgent>()
agent.sendMessage(
    sessionId = "session-123",
    text = "Привет!",
    branchNodeId = null
)

// Запустить ReAct агента
val runner = AgentRunner(api = get(), memory = get(), mcpProviders = get())
val result = runner.run(sessionId = "session-123", userInput = "Найди товар молоко")
```
