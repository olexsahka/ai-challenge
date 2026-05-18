# ADR: feature-rag1 — RAG (индексация документов + 2 стратегии chunking)
Дата: 2026-05-17
Статус: PROPOSED

## Контекст
В Android-приложении нужен механизм RAG поверх большого локального документа `database-concepts.txt` (Oracle Database Concepts 21c, ~36090 строк / ~1.3 MB) — чтобы пользователь мог задавать вопросы по содержимому без отправки всего файла в LLM. Бэкенд не используется: пайплайн (chunking → embeddings → индекс → retrieval) выполняется полностью on-device, индекс хранится в Room (SQLite). Урок 21 курса.

## Решение
Реализовать пайплайн `IngestionPipeline` в Android приложении (модуль `app/`, новый пакет `data/rag/`):

1. **Loader** читает `database-concepts.txt` из `assets/` (файл копируется в `app/src/main/assets/database-concepts.txt`).
2. **Chunker** разбивает текст одним из двух способов (стратегия выбирается в настройках):
   - `FIXED_SIZE` — окно ~500 символов с overlap ~50 символов.
   - `STRUCTURAL` — по заголовкам файла (см. эвристику в Spec): `Chapter N`, заголовки разделов, нумерованные пункты `N.N`, разделители страниц `Database Concepts / F31733-09 ...`. Чанки группируются в пределах одной секции.
3. **Embedder** считает векторное представление каждого чанка. По умолчанию — `TfIdfEmbedder` (детерминированный baseline без внешних зависимостей). ⚠️ Альтернатива `ML Kit Smart Reply` / `sentence-transformers` — см. Открытые вопросы.
4. **Index** сохраняется в Room: новая таблица `rag_chunks` (id, source, title, section, chunkId, text, embeddingBlob, strategy, createdAt) + новая таблица `rag_vocabulary` для словаря TF-IDF (term → idf, term → dimensionIndex). При смене стратегии — обе таблицы очищаются и заполняются заново.
5. **Retriever** ищет top-K чанков по cosine similarity между эмбеддингом вопроса и эмбеддингами чанков. На MVP top-K=4.
6. **RAG Chat UI** (заглушка на MVP) — экран с описанием того, что это RAG по `database-concepts.txt`. Сам чат с retrieval вынесен в Out of scope текущей итерации (см. ⚠️ Открытые вопросы / Out of scope в Spec).

UI:
- `MainActivity` оборачивает контент в Scaffold с `TabRow` (две вкладки: `Chat` слева, `RAG Chat` справа).
- `ChatTab` показывает существующий `AgentScreen()` (без изменений).
- `RagChatTab` — новый Composable-заглушка с текстом «RAG-чат по файлу database-concepts.txt. Индексировано N чанков, стратегия: …».
- `RagSettingsScreen` — новый экран настроек: `Dropdown` (`FIXED_SIZE` / `STRUCTURAL`) + кнопка `Сохранить`. Открывается из существующего меню настроек `AgentScreen` (см. ⚠️ Открытые вопросы).

## Архитектурные решения

### Слой данных
- [x] Новые Room таблицы: `rag_chunks`, `rag_vocabulary`. Версия БД повышается с 10 до 11 (destructive migration уже включён в `AppDatabase.fallbackToDestructiveMigration()`).
- [x] Новые DAO: `RagChunkDao`, `RagVocabularyDao`.
- [x] Новая SharedPreferences группа `rag_prefs`: `chunking_strategy` (`FIXED_SIZE` | `STRUCTURAL`), `last_indexed_at` (Long), `indexed_strategy` (для определения «стратегия изменилась»).
- [x] Нет MCP клиента — всё локально.

### Слой shared/
- [ ] На MVP — НЕ трогаем `shared/`. Весь RAG-код в `app/data/rag/`. Перенос в `shared/` отложен на будущую KMP-фазу (см. KMP_MIGRATION_PLAN.md).
- [ ] Нет изменений `AgentRunner` / `LLMAgent` — RAG Chat не подключается к LLM на MVP.

### Слой app/
- [x] Новый пакет `data/rag/`:
  - `RagAssetLoader` (читает текст из assets)
  - `Chunker` интерфейс + `FixedSizeChunker`, `StructuralChunker`
  - `Embedder` интерфейс + `TfIdfEmbedder`
  - `RagIndexer` (orchestrator: loader → chunker → embedder → DAO)
  - `RagRetriever` (cosine similarity, top-K)
  - `RagRepository` (фасад: `isIndexed()`, `reindex(strategy)`, `getStrategy()`, `setStrategy(...)`)
- [x] Новый пакет `presentation/rag/`:
  - `RagChatScreen` (заглушка), `RagChatViewModel`
  - `RagSettingsScreen`, `RagSettingsViewModel`
- [x] `MainActivity` обновлён под `TabRow` (Chat / RAG Chat).
- [x] Новый Koin модуль `ragModule` (внутри `di/AppModule.kt`): `RagChunkDao`, `RagVocabularyDao`, `RagRepository`, `RagChatViewModel`, `RagSettingsViewModel`.

### Бэкенд
- [ ] **Не нужен**. Полностью on-device.

## Альтернативы рассмотренные

| Вариант | Отклонён потому что |
|---------|---------------------|
| Векторная БД (Chroma/SQLite-VSS) | Лишняя зависимость; Room уже в проекте, на ~36k строк достаточно линейного скана + cosine similarity |
| Загрузка файла из `Downloads/` через SAF | Усложняет MVP, файл известен и фиксирован — кладём в `assets/` |
| ML Kit Smart Reply embeddings | API заточено под короткие фразы для чатов, не под document retrieval. Нет публичного embedding API. |
| sentence-transformers (ONNX через onnxruntime-android) | +50-100 МБ модель в APK; настройка сложная для MVP. Возможный апгрейд в следующих итерациях. |
| TensorFlow Lite Universal Sentence Encoder | ~28 МБ модели + сложная подключение, переусложнение для baseline; оставляем как апгрейд. |
| Хранение эмбеддингов в `ByteArray` JSON | Дороже по месту и скорости, чем `BLOB` (FloatArray → ByteBuffer) |
| Перенести Chunker / Embedder в `shared/` сразу | Расширяет scope; на MVP всё в `app/`. Перенос будет отдельной задачей KMP-фазы 1. |
| Реализовать RAG-ответы с LLM сразу | Пользователь явно описал MVP как «заглушка» на RAG Chat экране — отложено в Out of scope |

## Последствия

### Плюсы
- Полный пайплайн RAG end-to-end в Android-приложении, без бэкенда.
- TF-IDF baseline не требует ML модели в APK — приложение остаётся компактным.
- Чёткая инкапсуляция: `Chunker` и `Embedder` — интерфейсы, легко подменить на ML Kit / ONNX в следующей итерации.
- Метаданные `source / title / section / chunkId` позволяют в будущем показывать ссылки на источник в ответе.

### Минусы / риски
- TF-IDF — bag-of-words: не понимает синонимы, не учитывает семантику. Качество retrieval ниже, чем у dense-эмбеддингов. → План: оставить интерфейс `Embedder` для апгрейда.
- Индексация 1.3 MB на устройстве с TF-IDF может занимать секунды (один проход по всем чанкам + IDF). → Гонять в `Dispatchers.IO`, показывать прогресс.
- Реиндексация при смене стратегии = удаление всех строк → если пользователь часто переключается, теряет время. Приемлемо для MVP.
- Размер БД вырастет на ~5-15 МБ (зависит от стратегии и размера словаря TF-IDF).

## Открытые вопросы — РЕШЕНЫ (2026-05-17)

- РЕШЕНО: **Embeddings-движок:** `TfIdfEmbedder` (on-device, без ML Kit, детерминированный baseline).

- РЕШЕНО: **Точка входа в "Настройки RAG":** кнопка «Настройки RAG» в существующем `SettingsDialog` → открывает новый экран `RagSettingsScreen`.

- РЕШЕНО: **Когда происходит первичная индексация:** ТОЛЬКО при нажатии «Сохранить» в `RagSettingsScreen`. При первом открытии вкладки RAG Chat — если индекс не создан, показывается сообщение «Индекс не создан, перейдите в Настройки RAG».

- РЕШЕНО: **Содержимое RAG Chat:** заглушка. Retrieval не реализовывать в текущей итерации. Если индекс не создан — показать «Индекс не создан, перейдите в Настройки RAG».

- РЕШЕНО: **Стратегия по умолчанию:** `FIXED_SIZE`.

- РЕШЕНО: **chunk_size / overlap / top-K:** 500 / 50 / 4. Метаданные: source, title, section, chunk_id.

- РЕШЕНО: **Миграция БД:** destructive migration (v10 → v11) — принято.