# Spec: feature-rag1 — RAG индексация локального документа
Версия: 1.0
Дата: 2026-05-17

## User Story
Как Android-пользователь, я хочу проиндексировать файл `database-concepts.txt` локально на устройстве и переключаться между обычным чатом и RAG-чатом, чтобы в будущем задавать вопросы по содержимому документа без отправки его целиком в LLM.

## Acceptance Criteria

- **AC-01:** В `MainActivity` поверх контента отображается `TabRow` с двумя вкладками: «Chat» (слева) и «RAG Chat» (справа). При запуске активна вкладка «Chat», в ней показывается существующий `AgentScreen()` без изменений.
- **AC-02:** При переключении на вкладку «RAG Chat»: если индекс создан — показывается текст «RAG-чат по файлу `database-concepts.txt`. Чанков в индексе: N. Стратегия chunking: {STRATEGY}.»; если индекс не создан (N=0, `last_indexed_at == 0L`) — показывается сообщение «Индекс не создан, перейдите в Настройки RAG». Автоматическая индексация при открытии вкладки не запускается.
- **AC-03:** В диалоге настроек (`SettingsDialog` в `AgentScreen`) добавлена кнопка «Настройки RAG», открывающая `RagSettingsScreen`. На экране есть выпадающий список выбора стратегии chunking с двумя вариантами: `FIXED_SIZE` («По фиксированному размеру») и `STRUCTURAL` («По структуре документа»). Под dropdown — кнопка «Сохранить».
- **AC-04:** При нажатии «Сохранить» в настройках RAG: если выбранная стратегия отличается от ранее сохранённой (или индекс ещё не создан) → полностью очищаются таблицы `rag_chunks` и `rag_vocabulary`, и запускается индексация с выбранной стратегией. Это единственный триггер первичной и повторной индексации. Если стратегия не изменилась — ничего не делается, отображается toast «Стратегия не изменилась». В обоих случаях экран закрывается после завершения.
- **AC-05:** Каждая запись в таблице `rag_chunks` имеет метаданные: `source` (значение `"database-concepts.txt"`), `title` (название документа, `"Oracle Database Concepts 21c"`), `section` (название раздела / главы, к которой относится чанк; для `FIXED_SIZE` — последний встреченный заголовок), `chunkId` (порядковый номер чанка в индексе, 0-based).
- **AC-06:** Индексация выполняется на `Dispatchers.IO`. UI остаётся отзывчивым. Во время индексации в `RagChatScreen` показывается индикатор «Индексация… {N}/{M}» (где M — общее число чанков для стратегии, N — обработано).
- **AC-07:** Реализованы две стратегии chunking (см. раздел «Алгоритмы»):
  - `FIXED_SIZE`: окно 500 символов, overlap 50 символов, разрыв по границе слова.
  - `STRUCTURAL`: по заголовкам документа (Chapter N, Part N, нумерованные подзаголовки уровня 1-3). Чанк = текст одной секции; если секция > 2000 символов — разбивается на под-чанки по 1500 символов с overlap 100.
- **AC-08:** Embedder реализован за интерфейсом `Embedder`. На MVP — `TfIdfEmbedder`: словарь строится при индексации, IDF сохраняется в `rag_vocabulary`, эмбеддинг каждого чанка нормализуется (L2) и кладётся в `embeddingBlob` как `FloatArray` через `ByteBuffer`.
- **AC-09:** Все классы chunking и embedding покрыты unit-тестами (минимум: оба чанкера на коротком фикстурном тексте; `TfIdfEmbedder` — нормализация, размерность). Используется существующая инфраструктура `Fake*Dao`.

### Edge cases / ошибки
- **AC-10:** При отсутствии `database-concepts.txt` в assets — `RagIndexer.reindex()` бросает `RagAssetMissingException`. UI показывает Snackbar «Файл документа не найден в приложении».
- **AC-11:** При пустом результате chunking (например, файл пустой) — индексация завершается без ошибок, `rag_chunks` остаётся пустым, в UI отображается «Чанков в индексе: 0» и предупреждение «Документ пуст».
- **AC-12:** Повторный вызов реиндексации, когда уже идёт индексация — игнорируется (флаг `RagRepository.isIndexing: StateFlow<Boolean>`).

## UI / UX

### Экраны

- **MainActivity (изменён):** оборачивает контент в `Scaffold { TabRow + HorizontalPager / when }`. Вкладки:
  - Tab 0: «Chat» → `AgentScreen()` (без изменений)
  - Tab 1: «RAG Chat» → `RagChatScreen()`
  - Состояние выбранной вкладки хранится в `rememberSaveable`.
- **RagChatScreen:** заглушка с текстом + индикатор индекса + кнопка «Индексировать сейчас» (только если индекс пуст и не идёт индексация).
- **RagSettingsScreen:** Dropdown (`ExposedDropdownMenuBox`) с двумя стратегиями + кнопка «Сохранить» + кнопка «Назад».
- **SettingsDialog (изменён):** добавлен пункт меню «Настройки RAG» (Button), который запускает навигацию на `RagSettingsScreen`. Способ навигации (Dialog overlay / отдельный Activity / Compose Navigation) выбирает планировщик.

### Состояния RagChatScreen

| Состояние | Что видит пользователь |
|-----------|------------------------|
| Loading (первый расчёт `isIndexed`) | `CircularProgressIndicator` по центру |
| Empty (`isIndexed == false`, `!isIndexing`) | «Индекс не создан, перейдите в Настройки RAG» (без кнопки автоиндексации) |
| Indexing | «Индексация… {N}/{M}». Линейный прогресс. Кнопки скрыты. |
| Success (`isIndexed == true`) | «RAG-чат по файлу database-concepts.txt. Чанков: {N}. Стратегия: {STRATEGY}.» |
| Error | Snackbar с текстом ошибки + кнопка «Повторить» |

### Состояния RagSettingsScreen

| Состояние | Что видит пользователь |
|-----------|------------------------|
| Loading current strategy | Dropdown disabled, progress на кнопке «Сохранить» |
| Idle | Dropdown активен, текущее значение выбрано |
| Saving (после нажатия «Сохранить» с изменением стратегии) | Кнопка «Сохранить» disabled с текстом «Реиндексация…». После завершения — экран закрывается. |
| Error при индексации | Snackbar «Ошибка индексации: {message}», dropdown снова активен |

## Алгоритмы

### Chunker: `FixedSizeChunker`
Вход: `text: String`, конфиг `(chunkSize=500, overlap=50)`.
Шаги:
1. Идём окнами по `chunkSize` символов, шаг = `chunkSize - overlap`.
2. На границе окна — расширяем границу до ближайшего пробельного символа в пределах ±20 символов, чтобы не резать слова.
3. Для каждого чанка определяем `section`: последний встреченный до этой позиции заголовок (см. эвристику ниже).
4. Возвращаем `List<RagChunk>` с `chunkId` от 0.

### Chunker: `StructuralChunker`
Вход: `text: String`.
Шаги:
1. Идём построчно. Эвристика заголовков (regex):
   - `^Part [IVX]+(\s+.*)?$` — крупная часть
   - `^Chapter \d+$` или `^\d+\s+[A-Z][^\n]{3,}$` — глава
   - `^[A-Z][A-Za-z0-9\s,:\-/]{3,80}$` без точки на конце, без цифр в начале — название секции (heuristic)
2. Между двумя соседними заголовками — содержимое секции. Тримим пустые строки и колонтитулы (`Database Concepts`, `F31733-...`, `Copyright ©`, `Page N of M`).
3. Если содержимое секции ≤ 2000 символов → один чанк.
4. Если > 2000 символов → разбиваем на подчанки по 1500 с overlap 100 (рекурсивно через тот же подход, что в `FixedSizeChunker`).
5. `section` = название текущей секции; `title` = `"Oracle Database Concepts 21c"`; `source` = `"database-concepts.txt"`.

### Embedder: `TfIdfEmbedder`
Вход: `List<RagChunk>` (все чанки одного прохода).
Шаги:
1. Tokenize: `text.lowercase().split(Regex("[^a-z0-9_]+"))` → `tokens`. Отфильтровать пустые и стоп-слова (минимальный список: a, the, of, is, and, or, to, in, on, for).
2. Построить словарь: `term → index` (`Map<String, Int>`) — все уникальные термины во всех чанках.
3. Посчитать DF (document frequency) для каждого терма, затем IDF: `idf(t) = ln((1 + N) / (1 + df(t))) + 1`.
4. Для каждого чанка построить TF-вектор → умножить на IDF → L2-нормализовать → `FloatArray(vocabulary.size)`.
5. Сохранить словарь и IDF в `rag_vocabulary`, эмбеддинги — в `rag_chunks.embeddingBlob` через `ByteBuffer.allocate(4 * size).order(LITTLE_ENDIAN).putFloat(...)` (детали в Tech Plan).

### Retriever (для будущих итераций; на MVP не используется в UI)
1. `query.toEmbedding(loadedVocabulary)` → `FloatArray`.
2. Линейный скан всех `rag_chunks`, считаем cosine similarity.
3. Возвращаем top-K=4 чанка.

## Данные

### Room

#### Таблица `rag_chunks`
| Поле | Тип | Описание |
|------|-----|----------|
| `id` | `Long PK autoGenerate` | Внутренний id |
| `chunkId` | `Int` | Порядковый номер чанка в индексе (0-based) |
| `source` | `String` | Имя файла-источника, `"database-concepts.txt"` |
| `title` | `String` | Название документа |
| `section` | `String?` | Название секции / главы |
| `text` | `String` | Текст чанка |
| `embedding` | `ByteArray` (BLOB) | Сериализованный `FloatArray` |
| `strategy` | `String` | `FIXED_SIZE` или `STRUCTURAL` (для аудита) |
| `createdAt` | `Long` | Timestamp индексации |

Индекс: на `(strategy, chunkId)`.

#### Таблица `rag_vocabulary`
| Поле | Тип | Описание |
|------|-----|----------|
| `term` | `String PK` | Токен |
| `dimensionIndex` | `Int` | Позиция в FloatArray |
| `idf` | `Float` | Inverse document frequency |
| `strategy` | `String` | К какой стратегии относится |

При смене стратегии — обе таблицы очищаются (`DELETE FROM ...`).

#### Миграция БД
- Версия БД: `10 → 11`. `fallbackToDestructiveMigration()` уже включён → существующие чаты будут стёрты. **Принять в Tech Plan**: можно ли в этом проекте wipe данных пользователя или нужна явная миграция? (см. Открытые вопросы в ADR).

### SharedPreferences (`rag_prefs`)
| Ключ | Тип | Значения | Дефолт |
|------|-----|----------|--------|
| `chunking_strategy` | String | `FIXED_SIZE` / `STRUCTURAL` | `FIXED_SIZE` |
| `last_indexed_at` | Long | Unix ms | 0L |
| `indexed_strategy` | String? | Стратегия, с которой текущий индекс был построен | null |

### Assets
- `app/src/main/assets/database-concepts.txt` — копия файла из `C:\Users\sutug\AndroidStudioProjects\MyApplication\database-concepts.txt`. Размер ~1.3 МБ → влияет на размер APK.

## API
Нет API. Никаких HTTP-запросов на бэкенд для этой фичи.

## MCP
Нет MCP. RAG работает полностью локально.

## Нефункциональные требования

- **Безопасность:** данных пользователя в RAG нет; читаем только встроенный в APK файл.
- **Производительность:**
  - Индексация ~36k строк / 1.3 МБ с TF-IDF должна укладываться в ≤ 30 секунд на среднем эмулятор (Pixel 6). Гонять в `Dispatchers.IO`, не блокировать UI.
  - Запрос top-K (для будущих итераций) — линейный скан, должен выполняться < 500 мс при < 10k чанков.
- **Размер APK:** +1.3 МБ от assets. Без ML моделей.
- **Тестируемость:**
  - Все классы `Chunker`, `Embedder` — internal/open для подмены в тестах.
  - DAO мокаются через `FakeRagChunkDao`, `FakeRagVocabularyDao` (паттерн, описанный в CLAUDE.md).
  - `RagAssetLoader` принимает интерфейс `(String) -> InputStream`, чтобы в тестах подавать строку напрямую без `Context`.

## Out of scope (текущая итерация)

- Поиск и LLM-ответы по индексу (Retriever есть в коде, но не используется в UI; RAG Chat = заглушка).
- Загрузка пользовательских документов (только встроенный `database-concepts.txt`).
- Dense эмбеддинги (ML Kit / sentence-transformers / TF Lite USE).
- Перенос RAG-классов в `shared/` (KMP).
- Удаление / перестроение индекса по триггеру extbox (только смена стратегии).
- Подсветка источника в ответе чата.
- Стрим прогресса индексации через WorkManager / foreground service (используем простую корутину).