# Android Tech Plan: feature-rag1 — RAG индексация локального документа

Дата: 2026-05-17
Статус: DRAFT
Планировщик: android-planner

## Входные документы
- Spec: docs/specs/feature-rag1.md (v1.0, 2026-05-17)
- ADR: docs/adr/feature-rag1.md (PROPOSED, решения закрыты 2026-05-17)
- Backend Plan: не нужен (бэкенд не используется)

---

## Задачи shared-разработчика (SH-*)

На MVP весь RAG-код размещается в `app/` — **не в `shared/`** (согласно ADR: «shared/ не трогаем, перенос отложен в KMP-фазу»).

> Поэтому SH-задачи в этой фиче относятся к чисто логическим, платформо-независимым компонентам внутри `app/data/rag/` — интерфейсы и алгоритмы без Android API.

| ID    | Задача | DoD | Зависимости |
|-------|--------|-----|-------------|
| SH-01 | Определить модели данных: `RagChunk`, `ChunkingStrategy` enum, `IndexProgress` | Файл `data/rag/model/RagChunk.kt` с полями: `id, chunkId, source, title, section, text, embedding: FloatArray?, strategy, createdAt`; `ChunkingStrategy` enum: `FIXED_SIZE, STRUCTURAL`; `IndexProgress(current: Int, total: Int)` | — |
| SH-02 | Интерфейс `Chunker` + `FixedSizeChunker` | `interface Chunker { fun chunk(text: String): List<RagChunk> }`; `FixedSizeChunker(chunkSize=500, overlap=50)` реализует окно с word-boundary snap ±20 символов; заполняет `section` из последнего заголовка; unit-тест на фикстурном тексте ≥ 3 чанков | SH-01 |
| SH-03 | `StructuralChunker` | Разбивает по regex-заголовкам (Part/Chapter/секция); секции > 2000 символов режет на подчанки 1500 + overlap 100; тримит колонтитулы; unit-тест на фикстурном тексте | SH-01 |
| SH-04 | Интерфейс `Embedder` + `TfIdfEmbedder` | `interface Embedder { fun embed(chunks: List<RagChunk>): EmbedResult }`; `data class EmbedResult(val chunks: List<RagChunk>, val vocabulary: Map<String, VocabEntry>)`; `data class VocabEntry(val dimensionIndex: Int, val idf: Float)`; `TfIdfEmbedder` строит словарь, считает DF→IDF, L2-нормализует FloatArray; unit-тест: нормализация (norma ≈ 1.0), размерность совпадает с vocab | SH-01 |
| SH-05 | `RagRetriever` (cosine similarity, top-K=4) | `RagRetriever.query(queryText: String, vocabulary: Map<String, VocabEntry>, chunks: List<RagChunk>, topK: Int = 4): List<RagChunk>` — строит эмбеддинг запроса из словаря, линейный скан, сортирует по убыванию cosine similarity; **не используется в MVP UI**, но реализован и покрыт unit-тестом | SH-01, SH-04 |

---

## Задачи UI-разработчика (UI-*)

Зона: `app/` — Room, DAO, Repository, ViewModel, Compose UI, Koin, MainActivity.

| ID    | Задача | DoD | Зависимости |
|-------|--------|-----|-------------|
| UI-01 | Room entities + DAO: `RagChunkEntity`, `RagVocabularyEntity`, `RagChunkDao`, `RagVocabularyDao` | Таблицы `rag_chunks` и `rag_vocabulary` с полями из Spec; индекс на `(strategy, chunkId)`; `RagChunkDao`: `insertAll`, `deleteAll`, `getAll`, `countByStrategy`; `RagVocabularyDao`: `insertAll`, `deleteAll`, `getAll`; `AppDatabase` версия 10→11 с `fallbackToDestructiveMigration()` (уже включён) | — |
| UI-02 | `FakeRagChunkDao` + `FakeRagVocabularyDao` для тестов | In-memory реализации аналогично существующим `FakeSessionDao` и пр.; используются в тестах SH-02..SH-05 и UI-03..UI-04 | UI-01 |
| UI-03 | `RagAssetLoader` | `class RagAssetLoader(private val openStream: (String) -> InputStream)`; метод `fun loadText(fileName: String): String`; бросает `RagAssetMissingException` если файл не найден; в production передаётся `{ context.assets.open(it) }`; в тестах — `{ ByteArrayInputStream(fixture.toByteArray()) }` | — |
| UI-04 | `RagIndexer` (orchestrator) | `class RagIndexer(loader, chunker, embedder, chunkDao, vocabDao)`; метод `suspend fun reindex(strategy: ChunkingStrategy, onProgress: (IndexProgress) -> Unit)`; очищает таблицы → грузит текст → чанкирует → embed → вставляет в Room батчами; прогресс репортится через `onProgress`; работает на `Dispatchers.IO` | SH-01..SH-04, UI-01, UI-03 |
| UI-05 | `RagRepository` (фасад) | `class RagRepository(prefs: SharedPreferences, indexer: RagIndexer, chunkDao: RagChunkDao)`; методы: `fun getStrategy(): ChunkingStrategy`, `fun setStrategy(s: ChunkingStrategy)`, `suspend fun isIndexed(): Boolean` (count > 0 && `last_indexed_at > 0`), `val isIndexing: StateFlow<Boolean>`, `suspend fun reindex(strategy: ChunkingStrategy, onProgress: (IndexProgress) -> Unit)` (guard против повторного вызова); SharedPreferences `rag_prefs`: `chunking_strategy` (default `FIXED_SIZE`), `last_indexed_at`, `indexed_strategy` | UI-04 |
| UI-06 | `assets/database-concepts.txt` — скопировать файл | Файл `database-concepts.txt` из корня проекта скопирован в `app/src/main/assets/database-concepts.txt`; сборка проходит (`./gradlew assembleDebug`) | — |
| UI-07 | `MainActivity` — TabRow (Chat / RAG Chat) | `MainActivity` оборачивает контент в `Scaffold { Column { TabRow(selectedTabIndex) { Tab("Chat"); Tab("RAG Chat") }; when(selectedTabIndex) { 0 -> AgentScreen(); 1 -> RagChatScreen() } } }`; состояние вкладки в `rememberSaveable { mutableIntStateOf(0) }`; существующий `AgentScreen()` не изменяется | — |
| UI-08 | `RagChatViewModel` + `RagChatUiState` | ViewModel получает `RagRepository` через Koin; при `init` запускает `collectIndexStatus()` — подписка на `isIndexing` + однократный `isIndexed()`; `RagChatUiState` = sealed class (Loading, Empty, Indexing(current, total), Success(chunkCount, strategy), Error(message)) | UI-05 |
| UI-09 | `RagChatScreen` (Composable-заглушка) | Отображает состояния из Spec: Loading → `CircularProgressIndicator`; Empty → «Индекс не создан, перейдите в Настройки RAG»; Indexing → линейный `LinearProgressIndicator` + «Индексация… N/M»; Success → «RAG-чат по файлу database-concepts.txt. Чанков: N. Стратегия: STRATEGY.»; Error → Snackbar + кнопка «Повторить» | UI-08 |
| UI-10 | `RagSettingsViewModel` + `RagSettingsUiState` | ViewModel получает `RagRepository` через Koin; `RagSettingsUiState` = sealed class (Loading, Idle(currentStrategy), Saving, Error(message)); `fun onSave(selected: ChunkingStrategy)` — если стратегия не изменилась → Toast «Стратегия не изменилась», иначе → reindex через `RagRepository` с прогрессом в `Saving`; после завершения → `navigateBack` event через `SharedFlow` | UI-05, SH-01 |
| UI-11 | `RagSettingsScreen` (Composable) | `ExposedDropdownMenuBox` с `FIXED_SIZE` / `STRUCTURAL`; кнопка «Сохранить» (disabled в состоянии Saving, текст «Реиндексация…»); кнопка «Назад» / TopAppBar с навигацией назад; Snackbar при ошибке; принимает `onNavigateBack: () -> Unit` | UI-10 |
| UI-12 | Навигация SettingsDialog → RagSettingsScreen | В `AgentScreen` при показе `ContextSettingsSheet` добавляется кнопка «Настройки RAG» (OutlinedButton); нажатие устанавливает `showRagSettings = true` (локальный `rememberSaveable`); при `showRagSettings == true` — `RagSettingsScreen` отображается поверх (отдельный Composable в том же Scaffold или через условный `if`); при `onNavigateBack` — `showRagSettings = false` | UI-11 |
| UI-13 | Koin `ragModule` в `AppModule.kt` | Добавить `ragModule` с: `single { RagAssetLoader { get<Context>().assets.open(it) } }`, `single { RagChunkDao из AppDatabase }`, `single { RagVocabularyDao из AppDatabase }`, `single { RagIndexer(...) }`, `single { RagRepository(...) }`, `viewModel { RagChatViewModel(get()) }`, `viewModel { RagSettingsViewModel(get()) }` | UI-01, UI-03, UI-04, UI-05, UI-08, UI-10 |

---

## Сигнатуры

### SH-01: Модели данных

```kotlin
// app/src/main/java/com/example/myapplication/data/rag/model/RagChunk.kt
data class RagChunk(
    val chunkId: Int,
    val source: String,
    val title: String,
    val section: String?,
    val text: String,
    val embedding: FloatArray? = null,
    val strategy: ChunkingStrategy,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ChunkingStrategy { FIXED_SIZE, STRUCTURAL }

data class IndexProgress(val current: Int, val total: Int)

data class VocabEntry(val dimensionIndex: Int, val idf: Float)

data class EmbedResult(
    val chunks: List<RagChunk>,
    val vocabulary: Map<String, VocabEntry>
)
```

### SH-02/SH-03: Chunker интерфейс

```kotlin
// app/src/main/java/com/example/myapplication/data/rag/Chunker.kt
interface Chunker {
    fun chunk(text: String): List<RagChunk>
}

// app/src/main/java/com/example/myapplication/data/rag/FixedSizeChunker.kt
class FixedSizeChunker(
    private val chunkSize: Int = 500,
    private val overlap: Int = 50
) : Chunker {
    override fun chunk(text: String): List<RagChunk>
    private fun snapToWordBoundary(text: String, pos: Int, range: Int = 20): Int
    private fun extractLastHeading(text: String, upToPos: Int): String?
}

// app/src/main/java/com/example/myapplication/data/rag/StructuralChunker.kt
class StructuralChunker : Chunker {
    override fun chunk(text: String): List<RagChunk>
    private fun isHeading(line: String): Boolean
    private fun isFooter(line: String): Boolean
    private fun splitLargeSection(text: String, section: String): List<RagChunk>
}
```

### SH-04: Embedder интерфейс

```kotlin
// app/src/main/java/com/example/myapplication/data/rag/Embedder.kt
interface Embedder {
    fun embed(chunks: List<RagChunk>): EmbedResult
}

// app/src/main/java/com/example/myapplication/data/rag/TfIdfEmbedder.kt
class TfIdfEmbedder : Embedder {
    override fun embed(chunks: List<RagChunk>): EmbedResult
    private fun tokenize(text: String): List<String>
    private fun l2normalize(vector: FloatArray): FloatArray
    companion object {
        val STOP_WORDS = setOf("a", "the", "of", "is", "and", "or", "to", "in", "on", "for")
    }
}
```

### SH-05: RagRetriever

```kotlin
// app/src/main/java/com/example/myapplication/data/rag/RagRetriever.kt
class RagRetriever {
    fun query(
        queryText: String,
        vocabulary: Map<String, VocabEntry>,
        chunks: List<RagChunk>,
        topK: Int = 4
    ): List<RagChunk>
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float
}
```

### UI-01: Room Entities + DAO

```kotlin
// app/src/main/java/com/example/myapplication/data/db/entity/RagChunkEntity.kt
@Entity(
    tableName = "rag_chunks",
    indices = [Index(value = ["strategy", "chunkId"])]
)
data class RagChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Int,
    val source: String,
    val title: String,
    val section: String?,
    val text: String,
    val embedding: ByteArray,   // FloatArray через ByteBuffer LITTLE_ENDIAN
    val strategy: String,
    val createdAt: Long
)

// app/src/main/java/com/example/myapplication/data/db/entity/RagVocabularyEntity.kt
@Entity(tableName = "rag_vocabulary")
data class RagVocabularyEntity(
    @PrimaryKey val term: String,
    val dimensionIndex: Int,
    val idf: Float,
    val strategy: String
)

// app/src/main/java/com/example/myapplication/data/db/dao/RagChunkDao.kt
@Dao
interface RagChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<RagChunkEntity>)

    @Query("DELETE FROM rag_chunks")
    suspend fun deleteAll()

    @Query("SELECT * FROM rag_chunks WHERE strategy = :strategy ORDER BY chunkId ASC")
    suspend fun getAll(strategy: String): List<RagChunkEntity>

    @Query("SELECT COUNT(*) FROM rag_chunks WHERE strategy = :strategy")
    suspend fun countByStrategy(strategy: String): Int

    @Query("SELECT COUNT(*) FROM rag_chunks")
    suspend fun countAll(): Int
}

// app/src/main/java/com/example/myapplication/data/db/dao/RagVocabularyDao.kt
@Dao
interface RagVocabularyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vocab: List<RagVocabularyEntity>)

    @Query("DELETE FROM rag_vocabulary")
    suspend fun deleteAll()

    @Query("SELECT * FROM rag_vocabulary WHERE strategy = :strategy")
    suspend fun getAll(strategy: String): List<RagVocabularyEntity>
}
```

### UI-03: RagAssetLoader

```kotlin
// app/src/main/java/com/example/myapplication/data/rag/RagAssetLoader.kt
class RagAssetMissingException(fileName: String) : Exception("Asset not found: $fileName")

class RagAssetLoader(private val openStream: (String) -> InputStream) {
    fun loadText(fileName: String): String  // throws RagAssetMissingException
}
```

### UI-04: RagIndexer

```kotlin
// app/src/main/java/com/example/myapplication/data/rag/RagIndexer.kt
class RagIndexer(
    private val loader: RagAssetLoader,
    private val fixedSizeChunker: FixedSizeChunker,
    private val structuralChunker: StructuralChunker,
    private val embedder: Embedder,
    private val chunkDao: RagChunkDao,
    private val vocabDao: RagVocabularyDao
) {
    suspend fun reindex(
        strategy: ChunkingStrategy,
        onProgress: (IndexProgress) -> Unit
    )
}
```

### UI-05: RagRepository

```kotlin
// app/src/main/java/com/example/myapplication/data/rag/RagRepository.kt
class RagRepository(
    private val prefs: SharedPreferences,
    private val indexer: RagIndexer,
    private val chunkDao: RagChunkDao
) {
    val isIndexing: StateFlow<Boolean>

    fun getStrategy(): ChunkingStrategy
    fun setStrategy(strategy: ChunkingStrategy)
    fun getIndexedStrategy(): ChunkingStrategy?
    suspend fun isIndexed(): Boolean
    suspend fun getChunkCount(): Int
    suspend fun reindex(
        strategy: ChunkingStrategy,
        onProgress: (IndexProgress) -> Unit
    )
}
```

### UI-08: RagChatViewModel + UiState

```kotlin
// app/src/main/java/com/example/myapplication/presentation/rag/RagChatUiState.kt
sealed class RagChatUiState {
    object Loading : RagChatUiState()
    object Empty : RagChatUiState()  // индекс не создан
    data class Indexing(val current: Int, val total: Int) : RagChatUiState()
    data class Success(val chunkCount: Int, val strategy: ChunkingStrategy) : RagChatUiState()
    data class Error(val message: String) : RagChatUiState()
}

// app/src/main/java/com/example/myapplication/presentation/rag/RagChatViewModel.kt
class RagChatViewModel(
    private val ragRepository: RagRepository
) : ViewModel() {
    val uiState: StateFlow<RagChatUiState>
    fun retry()
}
```

### UI-10: RagSettingsViewModel + UiState

```kotlin
// app/src/main/java/com/example/myapplication/presentation/rag/RagSettingsUiState.kt
sealed class RagSettingsUiState {
    object Loading : RagSettingsUiState()
    data class Idle(val currentStrategy: ChunkingStrategy) : RagSettingsUiState()
    data class Saving(val progress: IndexProgress?) : RagSettingsUiState()
    data class Error(val message: String, val strategy: ChunkingStrategy) : RagSettingsUiState()
}

// app/src/main/java/com/example/myapplication/presentation/rag/RagSettingsViewModel.kt
class RagSettingsViewModel(
    private val ragRepository: RagRepository
) : ViewModel() {
    val uiState: StateFlow<RagSettingsUiState>
    val navigateBack: SharedFlow<Unit>

    fun onSave(selected: ChunkingStrategy)
}
```

### UI-11: RagSettingsScreen

```kotlin
@Composable
fun RagSettingsScreen(
    viewModel: RagSettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit
)
```

### UI-09: RagChatScreen

```kotlin
@Composable
fun RagChatScreen(
    viewModel: RagChatViewModel = koinViewModel()
)
```

---

## DTO / Data классы (сводка)

| Класс | Поля | Источник в Spec |
|-------|------|-----------------|
| `RagChunk` | chunkId, source, title, section, text, embedding, strategy, createdAt | AC-05, таблица rag_chunks |
| `RagChunkEntity` | id (PK auto), chunkId, source, title, section, text, embedding (ByteArray BLOB), strategy, createdAt | Spec §Данные/rag_chunks |
| `RagVocabularyEntity` | term (PK), dimensionIndex, idf, strategy | Spec §Данные/rag_vocabulary |
| `IndexProgress` | current: Int, total: Int | AC-06 |
| `VocabEntry` | dimensionIndex: Int, idf: Float | Spec §Embedder |
| `EmbedResult` | chunks: List<RagChunk>, vocabulary: Map<String, VocabEntry> | Spec §Embedder шаг 5 |

---

## AC покрытие

| AC | Задача | Описание |
|----|--------|----------|
| AC-01 | UI-07 | MainActivity TabRow: Chat + RAG Chat вкладки |
| AC-02 | UI-08, UI-09 | RagChatScreen: Empty «Индекс не создан» / Success «Чанков: N» |
| AC-03 | UI-11, UI-12 | RagSettingsScreen (dropdown + Сохранить) + кнопка в SettingsDialog |
| AC-04 | UI-10, UI-05 | RagSettingsViewModel.onSave() → RagRepository.reindex() / toast |
| AC-05 | SH-01, SH-02, SH-03 | Поля source/title/section/chunkId в RagChunk и chunker-ах |
| AC-06 | UI-04, UI-08 | RagIndexer.onProgress + RagChatViewModel Indexing state |
| AC-07 | SH-02, SH-03 | FixedSizeChunker (500/50) + StructuralChunker (2000/1500/100) |
| AC-08 | SH-04, UI-01 | TfIdfEmbedder + embeddingBlob ByteBuffer |
| AC-09 | SH-02, SH-03, SH-04, UI-02 | Unit-тесты чанкеров + эмбеддера + FakeDao |
| AC-10 | UI-03, UI-09 | RagAssetMissingException + Snackbar в RagChatScreen |
| AC-11 | UI-04, UI-08 | Пустой результат chunking → Success с chunkCount=0 + «Документ пуст» |
| AC-12 | UI-05 | RagRepository.isIndexing guard |

---

## Порядок реализации (рекомендуемый)

```
SH-01 (модели)
    ↓
SH-02 + SH-03 + SH-04 параллельно (чанкеры + эмбеддер)
    ↓
SH-05 (retriever — не блокирует UI)
    ↓
UI-01 (Room entities + DAO)
    ↓
UI-02 (FakeDao — нужны для тестов)
    ↓
UI-03 + UI-06 параллельно (RagAssetLoader + assets файл)
    ↓
UI-04 (RagIndexer)
    ↓
UI-05 (RagRepository)
    ↓
UI-08 + UI-10 параллельно (ViewModels)
    ↓
UI-09 + UI-11 параллельно (Screens)
    ↓
UI-07 (MainActivity TabRow)
UI-12 (SettingsDialog кнопка)
UI-13 (Koin ragModule)
```

---

## Структура файлов (новые файлы)

```
app/src/main/
├── assets/
│   └── database-concepts.txt                    (UI-06)
└── java/com/example/myapplication/
    ├── data/
    │   ├── db/
    │   │   ├── AppDatabase.kt                   (изменён: v10→v11, +2 entity)
    │   │   ├── dao/
    │   │   │   ├── RagChunkDao.kt               (UI-01)
    │   │   │   └── RagVocabularyDao.kt          (UI-01)
    │   │   └── entity/
    │   │       ├── RagChunkEntity.kt            (UI-01)
    │   │       └── RagVocabularyEntity.kt       (UI-01)
    │   └── rag/
    │       ├── model/
    │       │   └── RagChunk.kt                  (SH-01: RagChunk, ChunkingStrategy, IndexProgress, VocabEntry, EmbedResult)
    │       ├── Chunker.kt                       (SH-02: interface)
    │       ├── FixedSizeChunker.kt              (SH-02)
    │       ├── StructuralChunker.kt             (SH-03)
    │       ├── Embedder.kt                      (SH-04: interface)
    │       ├── TfIdfEmbedder.kt                 (SH-04)
    │       ├── RagRetriever.kt                  (SH-05)
    │       ├── RagAssetLoader.kt                (UI-03)
    │       ├── RagIndexer.kt                    (UI-04)
    │       └── RagRepository.kt                 (UI-05)
    ├── di/
    │   └── AppModule.kt                         (изменён: +ragModule, UI-13)
    └── presentation/
        ├── agent/
        │   └── AgentScreen.kt                   (изменён: +кнопка RAG Settings, UI-12)
        └── rag/
            ├── RagChatUiState.kt                (UI-08)
            ├── RagChatViewModel.kt              (UI-08)
            ├── RagChatScreen.kt                 (UI-09)
            ├── RagSettingsUiState.kt            (UI-10)
            ├── RagSettingsViewModel.kt          (UI-10)
            └── RagSettingsScreen.kt             (UI-11)
app/src/main/
└── java/com/example/myapplication/
    └── MainActivity.kt                          (изменён: TabRow, UI-07)

app/src/test/java/com/example/myapplication/
├── FakeRagChunkDao.kt                           (UI-02)
├── FakeRagVocabularyDao.kt                      (UI-02)
├── FixedSizeChunkerTest.kt                      (AC-09)
├── StructuralChunkerTest.kt                     (AC-09)
└── TfIdfEmbedderTest.kt                         (AC-09)
```

---

## Технические детали

### ByteBuffer для embedding BLOB
```kotlin
// Сериализация FloatArray → ByteArray
fun FloatArray.toByteArray(): ByteArray =
    ByteBuffer.allocate(4 * size).order(ByteOrder.LITTLE_ENDIAN)
        .also { buf -> forEach { buf.putFloat(it) } }
        .array()

// Десериализация ByteArray → FloatArray
fun ByteArray.toFloatArray(): FloatArray =
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        .let { buf -> FloatArray(size / 4) { buf.getFloat() } }
```

### Навигация SettingsDialog → RagSettingsScreen
Выбранный подход: **условный Composable в AgentScreen** (без Compose Navigation, без Activity).
В `AgentScreen` добавляется `var showRagSettings by rememberSaveable { mutableStateOf(false) }`.
При `showRagSettings == true` поверх основного содержимого показывается `RagSettingsScreen(onNavigateBack = { showRagSettings = false })` через `if (showRagSettings) { RagSettingsScreen(...) }` в том же `Scaffold`.

### Версия БД
`AppDatabase`: `version = 10` → `version = 11`. `fallbackToDestructiveMigration()` уже присутствует — явная миграция не нужна. Существующие данные чатов будут стёрты при первом запуске после обновления (принято в ADR).

### SharedPreferences ключи (`rag_prefs`)
```kotlin
const val PREFS_NAME = "rag_prefs"
const val KEY_STRATEGY = "chunking_strategy"      // default: "FIXED_SIZE"
const val KEY_LAST_INDEXED_AT = "last_indexed_at" // default: 0L
const val KEY_INDEXED_STRATEGY = "indexed_strategy" // default: null
```

---

## Нефункциональные требования (из Spec)

- Индексация в `Dispatchers.IO` — не блокирует main thread.
- Цель: ≤ 30 секунд на эмуляторе Pixel 6 для файла 1.3 МБ с TF-IDF.
- `RagAssetLoader` принимает `(String) -> InputStream` → тестируем без Android Context.
- Все `Chunker`, `Embedder` классы — `open` для наследования в тестах.
- Батчевая вставка в Room (`insertAll`) — не поштучно.

---

## Открытые вопросы

Все вопросы из ADR закрыты (2026-05-17). Новых вопросов нет.
