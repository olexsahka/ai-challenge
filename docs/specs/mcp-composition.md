# Spec: MCP Composition (multi-server BTC flow)
Версия: 1.1
Дата: 2026-05-14

## User Story
Как Android-пользователь приложения, я хочу, чтобы по событию из существующего SSE-потока Crypto MCP AI-агент автоматически провёл сквозной сценарий «получи BTC-цену → сохрани её файлом → найди предыдущий снимок → посчитай дельту», используя инструменты с **двух разных MCP-серверов** (Crypto MCP + mock save-server), и чтобы результат (дельта) появлялся прямо в чате `AgentScreen` как сообщение ассистента.

## Acceptance Criteria

- **AC-01 (триггер):** На каждое событие BTC из `ReminderManager.connectFlow()` внутри `ReminderForegroundService.startSse()` (строки 49–72) — если `cryptoMcpRepository.cryptoEnabled == true` И `taskSaveMcpRepository.enabled == true` И флаг `btc_composition_enabled == true` И существует `AgentViewModel._activeSessionId != null` — запускается ровно один `AgentRunner.run(...)` с длинным промптом BTC-флоу. Если предыдущий запуск ещё в работе (Mutex занят) — новое событие пропускается (single-flight).
- **AC-02 (get_price на Crypto MCP):** Первым шагом ReAct-цикла агент вызывает tool `get_price` с аргументом `{"symbol":"BTC"}` на Crypto MCP. В логе виден `ACTION: get_price` + `INPUT: {"symbol":"BTC"}`. OBSERVATION содержит подстроку `"Current price of BTC: $"`.
- **AC-03 (save_to_file на save-server):** Вторым шагом агент вызывает `save_to_file` на save-server с `filename` соответствующим regex `^btc-snapshot-\d+\.txt$` и `content` равным OBSERVATION из AC-02. Ответ tool содержит подстроку `"Файл сохранён: "` и абсолютный путь, заканчивающийся на имя файла.
- **AC-04 (list_files на save-server):** Третьим шагом агент вызывает `list_files` на save-server без аргументов и получает список файлов отсортированный по mtime DESC. Минимум один элемент — файл из AC-03.
- **AC-05 (дельта при первом снимке):** Если в списке AC-04 ровно один файл с префиксом `btc-snapshot-` → `FINAL_ANSWER` равен строке `"BTC snapshot saved. Дельта недоступна (первый снимок)."` и `read_file` НЕ вызывается. Эта строка записывается в чат `AgentScreen` через `agent.saveAssistantMessage(activeSessionId, finalAnswer, nodeId=null)`.
- **AC-06 (дельта при ≥2 снимках):** Если в списке ≥2 файла с префиксом `btc-snapshot-` → агент вызывает `read_file` для предыдущего файла (второго по mtime), извлекает число из `"Current price of BTC: $<X> USD"` обоих файлов и вычисляет дельту. `FINAL_ANSWER` соответствует regex `^BTC: \d+(\.\d+)?, было \d+(\.\d+)?, дельта [+\-]?\d+(\.\d+)?$`. Эта строка записывается в чат `AgentScreen` через `agent.saveAssistantMessage(...)`.
- **AC-07 (ошибка любого tool):** Если любой tool возвращает строку начинающуюся с `Error:` или произошёл сетевой сбой — `AgentRunner` завершает текущий run без падения процесса. Сообщение `"Composition flow failed: <reason>"` логируется через `Log.e("BtcComposition", ...)`. В чат `AgentScreen` НЕ пишется. Следующее SSE-событие обработается независимо.
- **AC-08 (выключено):** Если хоть один из флагов (`cryptoEnabled`, `taskSave.enabled`, `btc_composition_enabled`) выключен ИЛИ `_activeSessionId == null` — `runBtcCompositionFlow()` возвращается мгновенно без вызовов MCP, в логе `Log.d("BtcComposition", "skip: <reason>")`.
- **AC-09 (роутинг):** После `connect()` обоих провайдеров в `toolToProvider` присутствуют ключи: `get_price`, `get_price_history`, `subscribe_*`, `list_subscriptions`, `cancel_subscription` → `CryptoMcpRepository`; `save_to_file`, `list_files`, `read_file` → `StatelessMcpRepository(named("taskSave"))`. Покрывается unit-тестом.
- **AC-10 (тесты):** Минимум 5 unit-тестов на новый код Android (`AgentViewModel.runBtcCompositionFlow` или `CompositionFlowUseCase`):
  1. single-flight (второй параллельный вызов — no-op),
  2. выключенные флаги (`btc_composition_enabled=false` → no-op),
  3. отсутствие активной сессии (`_activeSessionId==null` → no-op),
  4. успешный run с 1 файлом → строка «первый снимок» сохранена через `saveAssistantMessage`,
  5. ошибка `save_to_file` → сообщение в logcat, в чат ничего не пишется.
- **AC-11 (тесты backend save-server):** Минимум 3 unit-теста на новый код save-server (по образцу `SaveHandlerTest.kt`):
  1. `list_files` на пустом каталоге возвращает `"(empty)"`,
  2. `list_files` сортирует по mtime DESC,
  3. `read_file` с несуществующим файлом возвращает `"Error: file 'X' not found"`.

## UI / UX (Android)

### Куда выводится дельта
**В чат `AgentScreen` активной сессии** (подтверждено пользователем). Реализация — стандартный паттерн `agent.saveAssistantMessage(sessionId, finalAnswerText, nodeId = null)` (см. `AgentViewModel.runAgentWithMcp`, строки 171–175). Текст появляется в `messages: StateFlow<List<Message>>` (строки 96–102) и автоматически рендерится UI.

### Экраны / поверхности
- **`AgentScreen` (существующий)** — новое assistant-сообщение в активной сессии:
  - первый снимок: `"BTC snapshot saved. Дельта недоступна (первый снимок)."`
  - последующие: `"BTC: 67200, было 67050, дельта +150"`
  - ошибки: НЕ пишутся в чат (только logcat).
- **`AgentSettingsScreen`** или соответствующий settings-блок (где лежит `cryptoEnabled` toggle) — добавляется toggle **«BTC композиция (Crypto MCP → save → diff)»**, привязанный к флагу `btc_composition_enabled` в `SharedPreferences("mcp_composition_prefs")`. Default OFF.
- **`ReminderForegroundService` foreground-notification** — НЕ меняется. Продолжает показывать стандартный статус Crypto Reminders, как сейчас.

### Состояния
- **Loading:** во время выполнения текущего run видимых изменений нет (можно опционально добавить пустое placeholder-сообщение «BTC композиция: считаю...», убираемое по окончании — не обязательно для MVP).
- **Success (первый снимок):** новое assistant-сообщение в чате с текстом «BTC snapshot saved. Дельта недоступна (первый снимок).»
- **Success (с дельтой):** новое assistant-сообщение в чате с текстом формата `BTC: <current>, было <previous>, дельта <+/-X>`.
- **Error:** в чат ничего не пишется. `Log.e("BtcComposition", "Composition flow failed: <reason>", throwable)`. Foreground-уведомление не меняется.
- **Empty / выключено:** триггер пришёл, но любой флаг выключен или нет активной сессии — никаких видимых изменений, `Log.d("BtcComposition", "skip: <reason>")`.

## API (backend — save-server)

Доработка модуля `save-server/` (`C:\Users\sutug\IdeaProjects\MCP-mock-composition-tool-server\save-server\`), порт 8083, endpoint `/mcp`. Никаких изменений в Android-side API не вводится — Android вызывает эти tool-ы стандартным `StatelessMcpClient.callTool(...)`.

### Существующий tool (без изменений)
```
tool: save_to_file
input:  { "filename": String, "content": String }    // оба обязательны
output: text — "Файл сохранён: <absolutePath>"
        Невалидное имя → "Error: filename '<...>' is invalid after sanitization"
        Пустое имя → "Error: filename must not be blank"
sanitization: regex [^a-zA-Z0-9._\-] → "_"
storage: каталог /tmp/mcp-results/ (создаётся при старте handler-а)
```

### Новые MCP-инструменты на save-server

```
tool: list_files
description: List files in /tmp/mcp-results/ sorted by modified time (newest first).
input:  {}    // объект без обязательных полей
output: text — построчно "<filename>\t<size>\t<modified-iso>", разделитель строк "\n".
        Если каталог пуст или не существует — единственная строка "(empty)".
        Сортировка: File.lastModified() DESC.
```

```
tool: read_file
description: Read text content of a file from /tmp/mcp-results/.
input:  { "filename": String }    // обязательно; имя санитизируется по тому же правилу, что и save_to_file
output: text — содержимое файла как plain text (UTF-8).
        Если файл не существует — "Error: file '<safeName>' not found".
        Если имя пустое/инвалидно после санитизации — "Error: invalid filename".
```

### Конвенция имени снимков BTC (договор клиент-сервер)
```
filename pattern: btc-snapshot-<unix-millis>.txt
sort key:         strict lexicographic == chronological (millis монотонно растут)
prefix filter:    клиент (LLM) фильтрует список AC-04 по префиксу "btc-snapshot-"
                  → list_files на сервере НЕ фильтрует, отдаёт всё содержимое каталога
```

### JSON-RPC формат
Те же `tools/list` и `tools/call` что и для существующего `save_to_file` (см. `SaveHandler.kt`) — все три tool-а на одном порту 8083, одном endpoint `/mcp`. Расширяется `toolsList()`-метод и добавляется ветка `toolCall()` для каждого нового имени.

## MCP — Android side

Никаких новых `McpProviderFacade`-реализаций. Используются уже зарегистрированные в `di/AppModule.kt`:

```
Crypto MCP    → CryptoMcpRepository       (http://10.0.2.2:8080/mcp, Bearer, stateless /mcp endpoint)
                  Реальные tools: get_price, get_price_history, subscribe_once, subscribe_periodic,
                                  subscribe_price_alert, list_subscriptions, cancel_subscription
                  Используется в этом флоу: только get_price (symbol=BTC)
Save MCP      → StatelessMcpRepository    (http://10.0.2.2:8083/mcp, no auth)
                  named("taskSave"), client named("taskSave")  (di/AppModule.kt:124,134)
                  Tools: save_to_file (есть), list_files + read_file (добавить)
```

`AgentRunner` принимает оба в `vararg McpProviderFacade`. `AgentViewModel` уже содержит обе ссылки (`AppModule.kt:138`).

### Новый метод
```kotlin
// AgentViewModel (либо вынести в CompositionFlowUseCase)
suspend fun runBtcCompositionFlow()
```

Внутренняя последовательность:
1. Проверка `btc_composition_enabled` в `SharedPreferences("mcp_composition_prefs")` (default false) — выход если false (AC-08).
2. Проверка `cryptoMcpRepository.cryptoEnabled && taskSaveMcpRepository.enabled` — выход если любой false (AC-08).
3. Считать `activeSessionId = _activeSessionId.value` — выход если null (AC-08).
4. `if (!compositionMutex.tryLock()) return` — single-flight (AC-01).
5. `try { val final = agentRunner.run(activeSessionId, BTC_COMPOSITION_PROMPT, cryptoMcpRepository, taskSaveMcpRepository) ; agent.saveAssistantMessage(activeSessionId, final, nodeId = null) } catch (e) { Log.e("BtcComposition", "Composition flow failed: ${e.message}", e) } finally { compositionMutex.unlock() }`.
6. Возвращает `Unit` (запись в чат — побочный эффект через `saveAssistantMessage`).

### Бридж SSE-сервиса → ViewModel
`ReminderForegroundService` существует как `Service` без прямого доступа к `AgentViewModel`. Допустимые варианты (выбор за planner-ом, не блокирующий ADR):
- (a) `AgentViewModel` хранится как `single` в Koin и инжектится в `Service` через `inject()` — но `viewModelScope` исчезает при уничтожении ViewModel, что неудобно. Используется `CoroutineScope(SupervisorJob() + Dispatchers.IO)` внутри сервиса для запуска suspend-метода use-case-а.
- (b) Вынести `runBtcCompositionFlow()` в отдельный `CompositionFlowUseCase` (singleton в Koin) — `ReminderForegroundService` инжектит его напрямую, `AgentViewModel` тоже инжектит его и проксирует.
Финальный выбор — за `android-planner`.

### Промпт длинного флоу
Точная формулировка — см. ADR раздел «Промпт длинного флоу». Промпт содержит:
- описание шагов 1–6,
- явное имя tool-а `get_price` с `{"symbol":"BTC"}`,
- явное имя файла `btc-snapshot-<unix-millis>.txt`,
- инструкцию извлечения числа из шаблона `"Current price of BTC: $<число> USD"`,
- точный формат `FINAL_ANSWER` для обоих случаев (первый снимок / есть дельта).

## Нефункциональные требования

- **Безопасность:**
  - `BuildConfig.CRYPTO_MCP_API_KEY` — единственный токен в флоу, уже хранится в `local.properties`.
  - Cleartext для `10.0.2.2:8080` и `10.0.2.2:8083` уже разрешён (CLAUDE.md → раздел MCP). Никаких новых исключений в `network_security_config.xml`.
  - Санитизация имени файла на save-server применяется и к `read_file`, и к `save_to_file` (re-use существующего паттерна `[^a-zA-Z0-9._\-]` → `_`).
  - `read_file` НЕ выполняет path traversal: использует `File(dir, safeName)` — `File.name` отбрасывает любые `../`.
- **Производительность:**
  - Single-flight: одновременно не более одного активного `runBtcCompositionFlow()` (`Mutex.tryLock()`).
  - Каждый run = до 5 LLM-вызовов и до 4 MCP-вызовов (`get_price`, `save_to_file`, `list_files`, `read_file`). На 1 BTC-событие в минуту — приемлемо при `btc_composition_enabled` под контролем пользователя.
- **Тестируемость:**
  - `StatelessMcpRepository` остаётся подклассуемым для `FakeSaveMcpRepository` в unit-тестах (паттерн CLAUDE.md → раздел Testing).
  - `CryptoMcpRepository` объявлен `open` — `FakeCryptoMcpRepository : CryptoMcpRepository(null, null)` с переопределёнными `connect()`, `callTool()`, `cryptoEnabled`.
  - На save-server (Kotlin/Ktor) — новые методы `listFiles()` и `readFile(name)` в `SaveHandler` покрываются TDD по образцу `SaveHandlerTest.kt` (минимум 3 теста на tool, см. AC-11).
  - Android unit-тесты используют `org.json:json:20240303` (уже в `testImplementation`).
- **Логирование:**
  - tag `BtcComposition` — старт, имя выбранного tool, имя сохранённого файла, результат дельты, причины skip, ошибки.

## Out of scope

- Полноценный экран для просмотра истории BTC-снимков (можно отдельной фичей).
- График/визуализация цены BTC во времени.
- Хранение снимков в Room/локальной БД — источник истины остаётся файлы на save-server.
- Переход на жёсткий 60-секундный таймер (WorkManager) — используется существующий SSE-trigger.
- Подключение `search-server` и `summarize-server` к BTC-флоу — они работают с задачами, не с BTC.
- Использование Crypto MCP `get_price_history` для дельты — фича именно про композицию двух серверов через save→list→read.
- Изменения в `AgentRunner` (`toolToProvider` уже поддерживает N серверов).
- Парсер JSON BTC-сводки на стороне Android — LLM сама извлекает цены и считает дельту.
- Multi-currency (ETH, USDT и т.д.) — только BTC в первой версии.
- Дедупликация сообщений в чате (если одно и то же событие BTC прилетает несколько раз — все будут записаны в чат).
- Удаление старых снимков `/tmp/mcp-results/` — каталог растёт неограниченно, чистка — отдельной фичей или ручным `docker volume`.
