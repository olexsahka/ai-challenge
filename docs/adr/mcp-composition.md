# ADR: MCP Composition (multi-server BTC flow)
Дата: 2026-05-14
Статус: PROPOSED

## Контекст
Нужен показательный сценарий **MCP Tool Composition**: AgentRunner в одной сессии последовательно вызывает инструменты с НЕСКОЛЬКИХ независимых MCP-серверов, корректно выбирая нужный tool на каждом шаге и передавая результат предыдущего шага во вход следующего.

Целевой бизнес-флоу:
1. Android получает событие BTC из существующего SSE-потока `ReminderForegroundService`.
2. Агент запрашивает свежую цену BTC у Crypto MCP сервера.
3. Сохраняет сводку файлом на mock save-server.
4. Находит «текущий» и «предыдущий» файлы со сводками BTC на save-server и вычисляет дельту между ними.
5. Дельта отображается в чате `AgentScreen` (новое сообщение от assistant в активной сессии).

## Решение

Реализовать сквозной флоу через `AgentRunner`, используя существующую инфраструктуру.

### MCP-серверы и их РЕАЛЬНЫЕ инструменты

**Crypto MCP server** (`http://10.0.2.2:8080/mcp`, Bearer auth via `BuildConfig.CRYPTO_MCP_API_KEY`) — Spring Boot, MCP spec 2024-11-05, stateless endpoint `/mcp` (SSE endpoint `/sse` в этом флоу НЕ используется). Реальные tool-ы (источник: `C:\Users\sutug\IdeaProjects\CryptoMcpServer\src\main\kotlin\org\example\cryptomcp\mcp\tools\`):

| Tool | Input | Output |
|------|-------|--------|
| `get_price` | `{ "symbol": "BTC" \| "ETH" \| "USDT" }` | text `"Current price of BTC: $<price> USD"` |
| `get_price_history` | `{ "symbol": String, "hours": Int? }` (1–168, default 24) | text — построчная история |
| `subscribe_once`, `subscribe_periodic`, `subscribe_price_alert`, `list_subscriptions`, `cancel_subscription` | подписки — в этом флоу не используются | — |

Android-обёртка: `CryptoMcpRepository : McpProviderFacade` (`app/src/main/java/com/example/myapplication/data/reminder/CryptoMcpRepository.kt`). Уже зарегистрирована в `di/AppModule.kt` и передаётся в `AgentViewModel`.

**Save MCP server** (`http://10.0.2.2:8083/mcp`, без auth) — Ktor mock из `C:\Users\sutug\IdeaProjects\MCP-mock-composition-tool-server\save-server\`. Существующий tool:

| Tool | Input | Output |
|------|-------|--------|
| `save_to_file` | `{ "filename": String, "content": String }` | text `"Файл сохранён: <absolutePath>"` (директория `/tmp/mcp-results/`, имя санитизируется `[^a-zA-Z0-9._\-]` → `_`) |

Доработка save-server в рамках этой фичи:

| Tool | Input | Output |
|------|-------|--------|
| `list_files` | `{}` | text — построчно `<filename>\t<size>\t<modified-iso>`, отсортировано по mtime DESC. Пустой каталог → `"(empty)"` |
| `read_file` | `{ "filename": String }` | text — содержимое файла. Отсутствует → `"Error: file '<safeName>' not found"`. Невалидное имя → `"Error: invalid filename"` |

Android-обёртка: `StatelessMcpRepository(named("taskSave"))` (`di/AppModule.kt:124,134`). Никаких новых клиентских классов не нужно — `StatelessMcpClient` уже умеет `tools/list` + `tools/call` без handshake.

### AgentRunner — без изменений
`AgentRunner` уже принимает `vararg McpProviderFacade` и строит `toolToProvider` на старте каждого `run()` через `connect()` всех провайдеров (CLAUDE.md → раздел AgentRunner.Routing). Композиция (выбор tool + порядок) — задача LLM в ReAct-цикле, max 6 итераций.

### Триггер
**Существующий `ReminderForegroundService`** (`app/src/main/java/com/example/myapplication/service/ReminderForegroundService.kt`, строки 49–72) уже слушает SSE-поток Crypto MCP сервера через `reminderManager.connectFlow().collect { event -> ... }`. На каждое событие BTC сервис вызывает `runBtcCompositionFlow()` (новый метод `AgentViewModel` или вынесенный use-case). Никакого WorkManager-таймера не вводим — частота определяется самим Crypto MCP сервером (подтверждено пользователем).

### Куда выводится дельта
**Чат `AgentScreen`** (подтверждено пользователем). Реализация: после завершения ReAct-цикла берётся `_activeSessionId` из `AgentViewModel` и вызывается `agent.saveAssistantMessage(activeSessionId, deltaText, nodeId = null)` — текст появится в чате как обычное сообщение ассистента (см. `AgentViewModel.runAgentWithMcp`, строки 171–175, шаблон уже существует).

Если активной сессии нет (`_activeSessionId == null`) → событие тихо пропускается, в logcat `Log.d("BtcComposition", "no active session, skip")`. Foreground-уведомление НЕ меняется (продолжает показывать стандартный статус Crypto Reminders).

### Парсинг цены BTC для дельты
**LLM сама извлекает число** из текста `get_price` (подтверждено пользователем). `get_price` возвращает текст вида `"Current price of BTC: $67200 USD"` — LLM в шаге 5 промпта вычитывает оба числа из текущего и предыдущего файла и считает разницу. Никакого Kotlin-парсера на стороне Android не вводим.

## Архитектурные решения

### Слой данных
- [x] **Никакой новой Room/SharedPreferences для BTC-снимков.** Источник истины — файлы на save-server (`/tmp/mcp-results/`).
- [x] **Никаких новых `McpProviderFacade`-реализаций.** `CryptoMcpRepository` и `StatelessMcpRepository(named("taskSave"))` уже существуют и подключены к `AgentViewModel` (`di/AppModule.kt:138`).
- [x] **Доработка save-server (бэкенд):** добавить tool-ы `list_files` и `read_file`. Без них шаг «найти текущий и предыдущий файл и сравнить» технически невозможен.

### Слой shared/
- [ ] Никаких изменений в commonMain — флоу собирается из существующих интерфейсов.
- [ ] `AgentRunner` НЕ меняется — `vararg McpProviderFacade` + `toolToProvider` уже поддерживают N серверов (CLAUDE.md).
- [ ] Новые domain-модели не требуются — сводки передаются как plain text через `save_to_file`/`read_file`.

### Слой app/
- [x] **Новый Composable-экран НЕ нужен** — дельта пишется в существующий `AgentScreen` через `agent.saveAssistantMessage(...)`.
- [x] **Изменение `AgentViewModel`:** добавить публичный метод `runBtcCompositionFlow()` (или вынесенный `CompositionFlowUseCase`), который вызывает `AgentRunner.run(...)` с готовым промптом длинного флоу и набором провайдеров (`cryptoMcpRepository`, `taskSaveMcpRepository`).
- [x] **`ReminderForegroundService`** в `startSse()` после `reminderManager.emitReminder(event)` дополнительно вызывает `agentViewModel.runBtcCompositionFlow()` (через `inject()` либо через bridge-функцию из application-scope).
- [ ] Новый Koin-модуль не нужен — все зависимости уже зарегистрированы в `di/AppModule.kt`.
- [x] **Гейтинг запуска:** флоу выполняется только когда `cryptoMcpRepository.cryptoEnabled == true` И `taskSaveMcpRepository.enabled == true` И флаг `btc_composition_enabled == true` (`SharedPreferences("mcp_composition_prefs")`, default OFF). Иначе — лог + skip без падения.
- [x] **Single-flight:** `Mutex.tryLock()` в `runBtcCompositionFlow()` — если предыдущий run ещё идёт, новое событие пропускается. Очередь не накапливается.

### Бэкенд (mock-composition-tool-server, модуль `save-server/`)
- [x] **Новые MCP инструменты на save-server (порт 8083), endpoint `/mcp`:**
  - `list_files` — без обязательных аргументов, возвращает текстовый список файлов в `/tmp/mcp-results/` отсортированных по mtime DESC. Формат строки: `<filename>\t<size>\t<modified-iso>`. Пустой каталог → `"(empty)"`.
  - `read_file` — `{ "filename": String }`, возвращает содержимое файла как text. Имя санитизируется тем же правилом, что и `save_to_file` (`[^a-zA-Z0-9._\-]` → `_`).
- [x] **Конвенция имени BTC-снимков:** агент инструктируется сохранять файл как `btc-snapshot-<unix-millis>.txt`. Префикс `btc-snapshot-` позволяет агенту фильтровать список (там же могут лежать файлы из старых сценариев). Сортировка лексикографическая совпадает с хронологической (millis монотонно растут).
- [ ] Новая таблица БД — нет (save-server file-based).
- [ ] Никаких изменений на Crypto MCP — все нужные tool-ы (`get_price`) уже есть.
- [ ] Никаких изменений на `search-server` / `summarize-server` — не задействуются.

### Промпт длинного флоу (system instruction для AgentRunner)

```
Ты выполняешь сквозной BTC-флоу. Используй только инструменты из tools/list, доступные тебе.

1. Вызови инструмент get_price с {"symbol":"BTC"} — он на Crypto MCP сервере.
   Сохрани полученный текст ответа (формат "Current price of BTC: $<число> USD").

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
   Извлеки число из строки "Current price of BTC: $<число> USD" в обоих файлах.
   Посчитай дельту = текущая - предыдущая.

6. FINAL_ANSWER в формате:
   "BTC: <текущая>, было <предыдущая>, дельта <+X | -X>"
```

## Альтернативы рассмотренные

| Вариант | Отклонён потому что |
|---------|---------------------|
| WorkManager-таймер вместо SSE | Дублирует уже работающую инфраструктуру `ReminderForegroundService`. Реальная «частота» BTC определяется Crypto MCP сервером. Пользователь подтвердил: SSE-trigger по умолчанию. |
| Хранить BTC-снимки в Room | Противоречит сути задачи «использовать инструменты с разных серверов». Файлы на save-server — единственный способ обеспечить шаги save → list → read через MCP-композицию. |
| Делать дельту локально в Kotlin (без MCP) | Тот же аргумент — выпадает второй сервер из композиции, теряется смысл фичи. |
| Использовать Crypto MCP `get_price_history` вместо save→list→read | Тогда дельта берётся из ОДНОГО сервера и фича перестаёт быть композицией. Сохраняется только как fallback, если save-server недоступен (не входит в MVP). |
| Реализовать `list_files` через выполнение `save_to_file` с маркером | Хак, ломает API save-server. Чище добавить два честных MCP-инструмента. |
| Использовать `search-server`/`summarize-server` | Они работают с задачами (Task), не с BTC. Их инструменты неприменимы. |
| Заводить новый `BtcMcpClient` отдельно от `StatelessMcpClient` | `StatelessMcpClient` уже подходит для save-server (нет handshake, нет auth). |
| Выводить дельту в notification | Пользователь явно выбрал «в чат AgentScreen». |
| Парсить цену Kotlin-парсером на Android | Пользователь явно выбрал «LLM сама извлекает число». |

## Последствия

**Плюсы:**
- Переиспользуется вся существующая MCP-инфраструктура: `AgentRunner` уже маршрутизирует tool-ы между провайдерами через `toolToProvider` (CLAUDE.md → AgentRunner.Routing).
- Демонстрирует именно композицию: один промпт, ≥2 MCP-сервера, агент сам выбирает tool на каждом шаге.
- Минимум новых сущностей: 2 новых MCP-tool на save-server + 1 метод в `AgentViewModel` + 1 промпт + 1 SharedPreferences-флаг.
- Результат виден в той же поверхности, что и обычные сообщения чата — не нужен отдельный UI.

**Минусы / риски:**
- Если LLM не справится с длинной цепочкой за 6 итераций ReAct-цикла (`AgentRunner.maxIter = 6`), флоу зависнет на последней итерации. Митигация: при сбое — лог + продолжение, не падать.
- save-server использует Docker-volume `mcp-results`; при пересоздании volume теряются все предыдущие снимки → шаг 4 промпта вернёт «первый снимок».
- `get_price` возвращает текст с одним числом — LLM должна стабильно его извлекать. Дрейф формата на стороне Crypto MCP сломает дельту. Митигация: в системном промпте явно указан шаблон `"$<число> USD"`.
- Cleartext `10.0.2.2:8083` и `10.0.2.2:8080` уже разрешены в `network_security_config.xml` — новых сетевых исключений не нужно.
- Каждый run AgentRunner = ~3–5 LLM-вызовов. На 1 BTC-событие в минуту = до 300 LLM-вызовов/час. Митигация: SharedPreferences-флаг `btc_composition_enabled` (default OFF) — пользователь включает вручную.
- Запись дельты как assistant-сообщения попадает под обычные стратегии памяти (FULL/SLIDING_WINDOW/...) и расходует контекст следующих обычных сообщений. Митигация принципиальная не требуется — пользователь сам решает.
- Если активной сессии нет в момент SSE-события — дельта теряется (никуда не пишется). Поведение явное, в логе.

## Открытые вопросы

Все блокирующие вопросы закрыты пользователем:
- **Триггер** = существующий SSE через `ReminderForegroundService` (default).
- **Куда выводить дельту** = в чат `AgentScreen` (`agent.saveAssistantMessage(...)`).
- **Парсинг цены BTC** = LLM сама извлекает число (default).

Остаются неблокирующие нюансы:
- ⚠️ НЕЯСНО (низкий приоритет): что делать, если `_activeSessionId == null` в момент SSE-события? → Дефолт: пропустить с `Log.d`. Уточнение не требуется для MVP.
- ⚠️ НЕЯСНО (низкий приоритет): нужно ли визуально отличать «системно сгенерированную» дельту в чате от обычных ответов? → Дефолт: нет, обычное assistant-сообщение. Можно добавить префикс `[BTC composition]` для удобства фильтрации.
