# Review mcp-composition — итерация 1
Дата: 2026-05-17
Вердикт: ❌ REJECTED

---

## Android

### Обзор
Фича добавляет сквозной BTC-флоу через MCP-композицию: `ReminderForegroundService` → SSE-событие → `CompositionFlowUseCase.runBtcCompositionFlow()` → `AgentRunner` с двумя провайдерами → результат в чат. SH-01/SH-02, UI-01..UI-08 реализованы.

### Проблемы

**[КРИТИЧНО] `CompositionFlowUseCaseTest.kt:80` — AgentRunner создаётся без MCP-провайдеров**
В `makeUseCase()` создаётся `AgentRunner(api, makeMemory())` — без `crypto` и `save` провайдеров. Тест №5 не проверяет реальную логику роутинга через `toolToProvider`. Тест проходит только потому что `FakeAnthropicApi` возвращает plain-text без ACTION-строк.
Исправление: `AgentRunner(api, makeMemory(), crypto, save)` — тогда тест верифицирует что `connect()` вызывается на обоих провайдерах.

**[ВАЖНО] Shared `AgentRunner` между `AgentViewModel` и `CompositionFlowUseCase`**
`CompositionFlowUseCase` получает тот же `AgentRunner` singleton что и `AgentViewModel`. Общая `conversationHistory` загрязняет контекст обычных диалогов пользователя.
Исправление: создать отдельный именованный AgentRunner в Koin:
```kotlin
single(named("composition")) { AgentRunner(get(), get(), get<CryptoMcpRepository>(), get<StatelessMcpRepository>(named("taskSave"))) }
```

**[ВАЖНО] Тест #6 не проверяет single-flight (AC-01)**
Тест запускает два **последовательных** вызова — не проверяет конкурентную single-flight семантику.
Исправление:
```kotlin
val job1 = async { uc.runBtcCompositionFlow() }
val job2 = async { uc.runBtcCompositionFlow() }
awaitAll(job1, job2)
verify(agent, times(1)).saveAssistantMessage(...)
```

**[ВАЖНО] `ReminderForegroundService.kt:29` — `Job()` вместо `SupervisorJob()`**
При падении корутина `compositionFlowUseCase.runBtcCompositionFlow()` весь `serviceScope` будет отменён и SSE-поток остановится.
Исправление: `CoroutineScope(Dispatchers.IO + SupervisorJob())`

**[ЗАМЕЧАНИЕ] `android.util.Log` в use-case**
Use-case использует `android.util.Log` напрямую — Android-платформенный вызов. Работает благодаря `testOptions { unitTests.returnDefaultValues = true }` в `build.gradle.kts`.

**[ЗАМЕЧАНИЕ] `AgentScreen.kt` toggle — проверить наличие Text-метки**
Убедиться что Row для BTC-toggle содержит `Text("BTC композиция (Crypto MCP → save → diff)")`.

### Соответствие плану

| Задача | Статус |
|--------|--------|
| SH-01 (AgentRunner не меняется) | ✅ ВЫПОЛНЕНО |
| SH-02 (ActiveSessionProvider) | ✅ ВЫПОЛНЕНО |
| UI-01 (BtcCompositionSettings) | ✅ ВЫПОЛНЕНО |
| UI-02 (BTC_COMPOSITION_PROMPT) | ✅ ВЫПОЛНЕНО |
| UI-03 (CompositionFlowUseCase) | ⚠️ ВАЖНО: shared AgentRunner |
| UI-04 (Koin регистрация) | ✅ ВЫПОЛНЕНО |
| UI-05 (AgentViewModel) | ✅ ВЫПОЛНЕНО |
| UI-06 (ReminderForegroundService) | ⚠️ ВАЖНО: Job() вместо SupervisorJob() |
| UI-07 (AgentScreen toggle) | ✅ ВЫПОЛНЕНО |
| UI-08 (TDD 5+ тестов) | ⚠️ КРИТИЧНО: тест AC-01 неправильный |

**Android вердикт: REJECTED**

---

## Backend

### Обзор
В `SaveHandler.kt` добавлены `listFiles()` и `readFile(filename)`, расширены `toolsList()` и `toolCall()`. Рефакторинг `wrapResultText` устранил дублирование. 19 тестов (8 старых + 11 новых).

### Проблемы

**[ЗАМЕЧАНИЕ] `SaveHandlerTest.kt` — общий `tempDir` для всех тестов**
Файлы, созданные в одном тесте, видны в другом. Тест `toolCall dispatches list_files` ожидает `"(empty)"` но может сломаться при смене порядка выполнения.
Исправление: использовать `@BeforeTest`/`@AfterTest` для изоляции каталога.

**[ЗАМЕЧАНИЕ] `SaveHandler.kt:133` — имя `"."` возвращает `"not found"` вместо `"invalid filename"`**
`File(".").name == "."`, точка разрешена regex, `!file.isFile` вернёт true. Семантически некорректно.
Исправление: добавить `if (safeName == "." || safeName == "..")` → `"Error: invalid filename"`.

**[ЗАМЕЧАНИЕ] `SaveHandlerTest.kt:98` — `setLastModified` может быть flaky на Windows/WSL**
Исправление: `assertTrue(file.setLastModified(1000L), "setLastModified failed")`.

**[ЗАМЕЧАНИЕ] Нет теста `readFile with path traversal reads safely`**
Симметрия с существующим тестом для `saveToFile`. Рекомендован, не блокирует.

### Соответствие плану

| ID | Статус |
|----|--------|
| BE-01 | ✅ ВЫПОЛНЕНО |
| BE-02 | ✅ ВЫПОЛНЕНО |
| BE-03 | ✅ ВЫПОЛНЕНО |
| BE-04 | ✅ ВЫПОЛНЕНО |
| BE-05 | ✅ ВЫПОЛНЕНО |
| BE-06 | ✅ ВЫПОЛНЕНО |
| BE-07 | ✅ ВЫПОЛНЕНО |

**Backend вердикт: APPROVED**

---

## Итог

КРИТИЧНО:
- Android: AgentRunner в тестах создаётся без MCP-провайдеров (`CompositionFlowUseCaseTest.kt:80`)

ВАЖНО:
- Android: Shared `AgentRunner` singleton между AgentViewModel и CompositionFlowUseCase — загрязнение conversationHistory
- Android: Тест single-flight проверяет последовательные, а не конкурентные вызовы
- Android: `Job()` вместо `SupervisorJob()` в `ReminderForegroundService`

ЗАМЕЧАНИЯ (не блокируют):
- Backend: общий tempDir в тестах, flaky setLastModified, семантика "." в readFile, нет теста path traversal для readFile
- Android: android.util.Log в use-case, проверить Text-метку toggle

**Итоговый вердикт: ❌ REJECTED**
Причина: 1 КРИТИЧНО + 3 ВАЖНО в Android. Backend APPROVED.
Следующий шаг: `/rework mcp-composition`
