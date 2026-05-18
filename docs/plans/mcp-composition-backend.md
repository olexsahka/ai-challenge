# Backend Tech Plan: MCP Composition (save-server tools: list_files + read_file)
Версия: 1.0
Дата: 2026-05-17
Источник: docs/specs/mcp-composition.md + docs/adr/mcp-composition.md

## Стек
- [x] **Ktor standalone** — `save-server/` модуль `MCP-mock-composition-tool-server` (порт 8083, endpoint `/mcp`)
- [ ] Spring Boot — НЕ применимо

**Репозиторий бэкенда:** `C:\Users\sutug\IdeaProjects\MCP-mock-composition-tool-server\save-server\`

**Текущее состояние save-server:**
- `src/main/kotlin/handler/SaveHandler.kt` — единственный tool `save_to_file`
- `src/main/kotlin/routing/McpRouting.kt` — JSON-RPC роутер на POST `/mcp`
- `src/main/kotlin/main.kt` — `embeddedServer(Netty, port = 8083)`
- `src/test/kotlin/SaveHandlerTest.kt` — 8 unit-тестов, образец TDD для нового кода
- Зависимости: Ktor 3.1.3, `org.json:json:20240303`, `kotlin("test")`, `ktor-server-test-host`
- jvmToolchain(22), Kotlin 2.3.0

## Стратегия TDD

Сначала пишем тесты (`SaveHandlerTest.kt`), потом имплементацию (`SaveHandler.kt`). Это соответствует AC-11 (минимум 3 теста на новый код save-server) и образцу существующих тестов.

Каждая BE-задача в порядке: **RED (тест) → GREEN (минимальная имплементация) → REFACTOR (если требуется)**.

## Задачи разработчика (BE-*)

| ID    | Задача                                                                           | DoD                                                                                                                                | Зависимости |
|-------|----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|-------------|
| BE-01 | Тесты `listFiles()` в `SaveHandlerTest.kt`                                       | 3 новых `@Test` метода: пустой каталог, сортировка по mtime DESC, формат строки `<name>\t<size>\t<modified-iso>`. Все три падают.   | —           |
| BE-02 | Имплементация `SaveHandler.listFiles(): String`                                  | Метод добавлен в `SaveHandler.kt`. Тесты BE-01 проходят (GREEN). `./gradlew :save-server:test` — 0 failures.                       | BE-01       |
| BE-03 | Тесты `readFile(name)` в `SaveHandlerTest.kt`                                    | 3 новых `@Test`: успешное чтение, несуществующий файл → `"Error: file 'X' not found"`, инвалидное имя → `"Error: invalid filename"`. Все три падают. | —           |
| BE-04 | Имплементация `SaveHandler.readFile(filename: String): String`                   | Метод добавлен в `SaveHandler.kt`. Санитизация через тот же regex `[^a-zA-Z0-9._\-]` → `_`. Path traversal через `File(...).name`. Тесты BE-03 проходят. | BE-03       |
| BE-05 | Обновить `toolsList(id)` — добавить дескрипторы `list_files` и `read_file`        | `toolsList` JSON содержит 3 tool-а. Тест `toolsList contains list_files tool` + `toolsList contains read_file tool` — проходят.    | BE-02, BE-04 |
| BE-06 | Расширить `toolCall(id, name, args)` — ветки для `list_files` и `read_file`      | `toolCall` корректно вызывает `listFiles()` / `readFile(name)`. Существующие тесты `save_to_file` не сломаны. 2 новых теста на `toolCall` для новых tool-ов — проходят. | BE-02, BE-04 |
| BE-07 | Регрессионный прогон + Dockerfile-sanity                                          | `./gradlew :save-server:test` — все тесты passed (8 старых + минимум 8 новых = ≥ 16). `./gradlew :save-server:jar` собирает jar без ошибок. | BE-05, BE-06 |

**Итого:** 7 задач, минимум 8 новых тестов (из них минимум 6 покрывают сами tool-ы по AC-11, остальные 2+ — `toolsList`/`toolCall` ветки).

## Файлы для изменения

| Файл                                                                                                                       | Действие | Что изменить                                                                                       |
|----------------------------------------------------------------------------------------------------------------------------|----------|----------------------------------------------------------------------------------------------------|
| `C:\Users\sutug\IdeaProjects\MCP-mock-composition-tool-server\save-server\src\main\kotlin\handler\SaveHandler.kt`          | EDIT     | + `fun listFiles(): String`, + `fun readFile(filename: String): String`, расширить `toolsList()` и `toolCall()` |
| `C:\Users\sutug\IdeaProjects\MCP-mock-composition-tool-server\save-server\src\test\kotlin\SaveHandlerTest.kt`              | EDIT     | + 8 новых `@Test` методов (см. BE-01, BE-03, BE-05, BE-06)                                          |

**НЕ менять:**
- `main.kt` — порт 8083 и `mcpRouting()` остаются как есть.
- `routing/McpRouting.kt` — JSON-RPC роутер дёргает `handler.toolCall(...)` обобщённо; новые tool-ы добавятся через ветки внутри `toolCall`, роутер не правится.
- `build.gradle.kts` — все нужные зависимости (`org.json`, `kotlin("test")`) уже есть.
- `Dockerfile` — без изменений.

## API контракт (MCP / JSON-RPC поверх POST /mcp)

### Существующий tool (для справки, БЕЗ изменений)

```
tools/call name="save_to_file"
Request:  { "filename": "btc-snapshot-1715900000000.txt", "content": "Current price of BTC: $67200 USD" }
Response: {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Файл сохранён: /tmp/mcp-results/btc-snapshot-1715900000000.txt"}],"isError":false}}
```

### Новый tool: list_files

**Описание:** List files in `/tmp/mcp-results/` sorted by modified time (newest first).

**`tools/list` дескриптор:**
```json
{
  "name": "list_files",
  "description": "List files in /tmp/mcp-results/ sorted by modified time (newest first). Returns one line per file: <filename>\\t<size>\\t<modified-iso>. Empty directory returns '(empty)'.",
  "inputSchema": {
    "type": "object",
    "properties": {},
    "required": []
  }
}
```

**`tools/call` запрос:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": { "name": "list_files", "arguments": {} }
}
```

**`tools/call` ответ (несколько файлов):**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [{
      "type": "text",
      "text": "btc-snapshot-1715900060000.txt\t34\t2026-05-17T08:34:20Z\nbtc-snapshot-1715900000000.txt\t34\t2026-05-17T08:33:20Z\nold-report.txt\t12\t2026-05-16T22:00:00Z"
    }],
    "isError": false
  }
}
```

**`tools/call` ответ (пустой каталог):**
```json
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"(empty)"}],"isError":false}}
```

**Формат строки списка:** `<filename>\t<size-bytes>\t<modified-iso8601-UTC>`
- разделитель полей: символ `\t` (tab)
- разделитель строк: `\n`
- `size` — `File.length()` (Long, в байтах)
- `modified-iso` — `Instant.ofEpochMilli(file.lastModified()).toString()` (формат `2026-05-17T08:34:20.123Z`)

### Новый tool: read_file

**Описание:** Read text content of a file from `/tmp/mcp-results/`.

**`tools/list` дескриптор:**
```json
{
  "name": "read_file",
  "description": "Read text content of a file from /tmp/mcp-results/. Filename is sanitized the same way as save_to_file. Returns plain UTF-8 text or 'Error: ...' on failure.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "filename": {
        "type": "string",
        "description": "Name of the file to read (e.g. 'btc-snapshot-1715900000000.txt')"
      }
    },
    "required": ["filename"]
  }
}
```

**`tools/call` запрос:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": { "name": "read_file", "arguments": { "filename": "btc-snapshot-1715900000000.txt" } }
}
```

**`tools/call` ответ (успех):**
```json
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Current price of BTC: $67200 USD"}],"isError":false}}
```

**`tools/call` ответ (файл не найден):**
```json
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Error: file 'missing.txt' not found"}],"isError":false}}
```

**`tools/call` ответ (инвалидное имя после санитизации):**
```json
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Error: invalid filename"}],"isError":false}}
```

**`tools/call` ответ (отсутствует обязательный аргумент filename):**
```json
{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"Missing filename argument"}}
```

**Поведение:**
- В `result.text` пишутся `"Error: ..."` строки (а не `error` JSON-RPC поле) — для **доменных** ошибок (файл не найден, имя инвалидно). Это соответствует контракту существующего `save_to_file` (см. `SaveHandler.saveToFile`).
- `error` JSON-RPC поле используется только при отсутствии **обязательного аргумента** `filename` (как сейчас в `save_to_file` при отсутствии `filename`/`content`). Соответствует существующему паттерну `buildError(id, -32602, ...)`.

## Сигнатуры (Kotlin)

```kotlin
package handler

class SaveHandler(private val resultsDir: String = "/tmp/mcp-results") {

    // СУЩЕСТВУЮЩИЕ — без изменений:
    fun toolsList(id: JsonElement?): String              // расширить — см. ниже
    fun toolCall(id: JsonElement?, toolName: String, arguments: JsonObject): String  // расширить — см. ниже
    fun saveToFile(filename: String, content: String): String
    fun buildError(id: JsonElement?, code: Int, message: String): String

    // НОВЫЕ:
    fun listFiles(): String
    fun readFile(filename: String): String

    // Внутренний helper (по желанию — можно inlinе): санитизация имени.
    // Использовать готовый паттерн из saveToFile: File(filename).name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
}
```

### Спецификация `listFiles()`

```kotlin
fun listFiles(): String {
    val dir = File(resultsDir)
    if (!dir.exists() || !dir.isDirectory) return "(empty)"
    val files = dir.listFiles { f -> f.isFile } ?: return "(empty)"
    if (files.isEmpty()) return "(empty)"
    return files
        .sortedByDescending { it.lastModified() }
        .joinToString("\n") { f ->
            val iso = java.time.Instant.ofEpochMilli(f.lastModified()).toString()
            "${f.name}\t${f.length()}\t$iso"
        }
}
```

### Спецификация `readFile(name)`

```kotlin
fun readFile(filename: String): String {
    if (filename.isBlank()) return "Error: invalid filename"
    val safeName = File(filename).name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
    if (safeName.isBlank() || safeName.all { it == '_' }) return "Error: invalid filename"
    val file = File(resultsDir, safeName)
    if (!file.exists() || !file.isFile) return "Error: file '$safeName' not found"
    return file.readText(Charsets.UTF_8)
}
```

### Расширение `toolsList(id)`

Внутри `JSONArray()` `result.tools` добавить два новых элемента к существующему `save_to_file` (см. формат выше — "API контракт"). Порядок не критичен (LLM ориентируется по `name`), но рекомендуется: `save_to_file`, `list_files`, `read_file`.

### Расширение `toolCall(id, toolName, arguments)`

```kotlin
fun toolCall(id: JsonElement?, toolName: String, arguments: JsonObject): String {
    return when (toolName) {
        "save_to_file" -> {
            // СУЩЕСТВУЮЩАЯ ВЕТКА — без изменений
            val filename = arguments["filename"]?.jsonPrimitive?.content
                ?: return buildError(id, -32602, "Missing filename argument")
            val content = arguments["content"]?.jsonPrimitive?.content
                ?: return buildError(id, -32602, "Missing content argument")
            wrapResultText(id, saveToFile(filename, content))
        }
        "list_files" -> wrapResultText(id, listFiles())
        "read_file" -> {
            val filename = arguments["filename"]?.jsonPrimitive?.content
                ?: return buildError(id, -32602, "Missing filename argument")
            wrapResultText(id, readFile(filename))
        }
        else -> buildError(id, -32602, "Unknown tool: $toolName")
    }
}

// Helper — выделить общий builder ответа (сейчас inline в save_to_file ветке).
// Это рефакторинг под Coding Guidelines CLAUDE.md "Устранение дублирования".
private fun wrapResultText(id: JsonElement?, text: String): String { /* existing JSON building */ }
```

**Примечание для разработчика:** существующая JSON-сборка `result.content[].text` в текущем `toolCall` дублируется. Вынести её в `private fun wrapResultText(id, text)` — это естественный рефакторинг при добавлении ещё двух веток (см. CLAUDE.md → Coding Guidelines → Устранение дублирования). НЕ обязательно как отдельная задача, но рекомендуется в рамках BE-06.

## Схема БД / Миграция

**Не применимо.** save-server — файловый storage (`/tmp/mcp-results/`). Никакой Flyway, никакого Postgres. Подтверждено в Spec строка 63 и ADR строка 85: "Новая таблица БД — нет (save-server file-based)".

## Безопасность

- **Path traversal:** `readFile` использует `File(filename).name` (отбрасывает любые `../`) + regex-санитизацию `[^a-zA-Z0-9._\-]` → `_`. Тот же паттерн что у `saveToFile` (см. существующий тест `saveToFile with path traversal stores safely`).
- **Размер файла:** `readFile` НЕ ограничивает размер читаемого файла. Это приемлемо, т.к. save-server — mock, файлы пишутся самим же агентом через `save_to_file`. Если в будущем понадобится ограничение — добавляется как отдельная фича.
- **`listFiles` info disclosure:** возвращает все файлы в каталоге без фильтрации. Это намеренно (по Spec строки 89–90: "клиент (LLM) фильтрует список AC-04 по префиксу `btc-snapshot-` → list_files на сервере НЕ фильтрует").
- **Auth:** save-server не использует auth (cleartext `10.0.2.2:8083` уже разрешён в `network_security_config.xml`, см. CLAUDE.md). Новые tool-ы НЕ вводят auth.
- **Размер запроса:** ограничение `body.length > 1_000_000` в `McpRouting.kt` уже применяется ко всем методам — новые tool-ы автоматически защищены.
- **Секреты:** новых секретов не вводится.

## Тестовая стратегия

**Подход:** TDD по образцу `SaveHandlerTest.kt`. Каждый тест:
1. Создаёт `SaveHandler(resultsDir = uniqueTempDir)`
2. (опционально) подготавливает файлы через `saveToFile(...)` или прямую запись `File(tempDir, "...").writeText(...)`
3. Вызывает целевой метод
4. Проверяет результат через `assertEquals` / `assertTrue` / `assertContains`

**Минимум по AC-11 (3 теста):**
1. `list_files` на пустом каталоге → `"(empty)"`
2. `list_files` сортирует по mtime DESC
3. `read_file` с несуществующим файлом → `"Error: file 'X' not found"`

**Расширенный список (минимум 8 новых тестов):**

| #  | Тест                                                                                                | BE-задача |
|----|-----------------------------------------------------------------------------------------------------|-----------|
| 1  | `listFiles on empty directory returns (empty)`                                                      | BE-01     |
| 2  | `listFiles sorts files by mtime DESC`                                                               | BE-01     |
| 3  | `listFiles format is name TAB size TAB iso-modified`                                                | BE-01     |
| 4  | `readFile returns content of existing file`                                                         | BE-03     |
| 5  | `readFile with non-existent name returns Error file X not found`                                    | BE-03     |
| 6  | `readFile with invalid filename returns Error invalid filename`                                     | BE-03     |
| 7  | `toolsList contains list_files and read_file tools` (проверка дескрипторов в `tools/list`)          | BE-05     |
| 8  | `toolCall dispatches list_files and read_file to correct handler methods`                           | BE-06     |

**Доп. (рекомендовано, не обязательно):**
- `readFile with path traversal stores safely` (по образцу существующего `saveToFile with path traversal` — убедиться что нельзя прочитать `/etc/passwd` через `../../../etc/passwd`).
- `toolCall read_file without filename returns -32602 error` (проверка JSON-RPC `error` ветки).

**Запуск:**
```bash
cd C:\Users\sutug\IdeaProjects\MCP-mock-composition-tool-server
./gradlew :save-server:test
```
Ожидание: 0 failures (8 старых + ≥ 8 новых = ≥ 16 тестов).

## AC покрытие

| AC      | Источник (spec)                                                              | Backend BE-задача   | Как проверяется                                                                                |
|---------|------------------------------------------------------------------------------|---------------------|------------------------------------------------------------------------------------------------|
| AC-03   | `save_to_file` существующий                                                  | (без изменений)     | Старый тест `saveToFile creates file with correct content` — регрессия в BE-07                |
| AC-04   | `list_files` без args, сортировка mtime DESC                                 | BE-02, BE-05, BE-06 | Тест `listFiles sorts files by mtime DESC` + `toolCall dispatches list_files` (BE-01/BE-06)   |
| AC-05   | Один файл `btc-snapshot-` → дельта недоступна                                | BE-02 (косвенно)    | Покрывается Android-стороной (AgentRunner парсит вывод `listFiles`); backend гарантирует формат |
| AC-06   | ≥ 2 файла → `read_file` для предыдущего, дельта                              | BE-04, BE-05, BE-06 | Тест `readFile returns content of existing file` + `toolCall read_file` (BE-03/BE-06)         |
| AC-07   | `read_file` несуществующий → `Error: file 'X' not found`                     | BE-04               | Тест `readFile with non-existent name returns Error` (BE-03)                                  |
| AC-11.1 | `list_files` на пустом каталоге → `(empty)`                                  | BE-02               | Тест `listFiles on empty directory returns (empty)` (BE-01)                                   |
| AC-11.2 | `list_files` сортирует по mtime DESC                                         | BE-02               | Тест `listFiles sorts files by mtime DESC` (BE-01)                                            |
| AC-11.3 | `read_file` несуществующий → `Error: file 'X' not found`                     | BE-04               | Тест `readFile with non-existent name returns Error` (BE-03)                                  |

**Покрытие: 8/8 backend-релевантных AC** (AC-01, AC-02, AC-08, AC-09, AC-10 — Android-side, не входят в backend Tech Plan).

## Контракт для Android-планировщика

После завершения BE-* задач Android-сторона может полагаться на следующие инварианты:

1. **Endpoint:** `http://10.0.2.2:8083/mcp` (POST, `Content-Type: application/json`).
2. **Без auth, без handshake** — `StatelessMcpClient` подходит как есть.
3. **`tools/list`** возвращает три tool-а: `save_to_file`, `list_files`, `read_file`.
4. **`list_files` без аргументов:**
   - Пустой каталог → `result.content[0].text == "(empty)"`
   - Иначе → строки `<name>\t<size>\t<iso>` разделённые `\n`, отсортированы mtime DESC.
5. **`read_file` с `{"filename": "<name>"}`:**
   - Успех → `result.content[0].text` == содержимое файла как UTF-8 plain text
   - Не найден → `"Error: file '<safeName>' not found"`
   - Инвалидное имя → `"Error: invalid filename"`
   - Отсутствует обязательный аргумент → JSON-RPC `error.code == -32602, error.message == "Missing filename argument"`
6. **Конвенция имени снимков:** `btc-snapshot-<unix-millis>.txt` — это **Android-side** convention, save-server её НЕ валидирует (любое имя принимается через `save_to_file`).

## Порядок выполнения

```
BE-01 (RED: listFiles tests)
   ↓
BE-02 (GREEN: listFiles impl) — тесты BE-01 проходят
   ↓
BE-03 (RED: readFile tests)
   ↓
BE-04 (GREEN: readFile impl) — тесты BE-03 проходят
   ↓
BE-05 (toolsList дескрипторы) — параллельно с BE-06 после BE-04
   ↓
BE-06 (toolCall ветки) — параллельно с BE-05 после BE-04
   ↓
BE-07 (полный регресс ./gradlew :save-server:test + jar)
```

## Команды для разработчика

```bash
# Запуск тестов
cd C:\Users\sutug\IdeaProjects\MCP-mock-composition-tool-server
./gradlew :save-server:test

# Сборка jar
./gradlew :save-server:jar

# Локальный запуск (для ручной проверки)
java -jar save-server/build/libs/save-server.jar
# затем: curl -X POST http://localhost:8083/mcp -H "Content-Type: application/json" \
#   -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Открытые вопросы

⚠️ НЕЯСНО (низкий приоритет, дефолты применены):
- **Формат `modified-iso`:** Spec строки 71–72 говорит `<modified-iso>` без точной формулировки. Дефолт: `java.time.Instant.toString()` → формат `2026-05-17T08:34:20.123Z` (ISO-8601 UTC с миллисекундами). Если Android-стороне нужен другой формат — поправить в `listFiles()`. Не блокирует.
- **Поведение `listFiles` при наличии поддиректорий в `/tmp/mcp-results/`:** Дефолт: фильтр `f.isFile` пропускает только файлы (не директории). Соответствует Spec, поддиректории не упомянуты.
- **Кодировка `read_file`:** Дефолт UTF-8 (`Charsets.UTF_8`). `saveToFile` пишет через `writeText(content)` без явной кодировки — Kotlin по умолчанию использует UTF-8. Совместимо.
