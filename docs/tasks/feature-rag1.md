# feature-rag1
Статус: 📐 PLANNED
Итерация Review: 0
Итерация Rework: 0

## Описание
RAG индексация локального файла `database-concepts.txt` (Oracle Database Concepts 21c) на Android: 2 стратегии chunking (FIXED_SIZE / STRUCTURAL) + on-device TF-IDF эмбеддинги + Room-индекс. UI: TabBar Chat / RAG Chat + новый экран «Настройки RAG».

## Документы
- ADR: docs/adr/feature-rag1.md
- Spec: docs/specs/feature-rag1.md
- Tech Plan (Android): docs/plans/feature-rag1-android.md — создаётся планировщиком
- Tech Plan (Backend): нет (бэкенд не нужен)

## Принятые решения (уточнения от пользователя, 2026-05-17)
- Embeddings: TF-IDF baseline (on-device, без ML Kit)
- Настройки RAG: кнопка в существующем SettingsDialog → новый экран RagSettingsScreen
- Первичная индексация: ТОЛЬКО при нажатии «Сохранить» в RagSettingsScreen
- RAG Chat если индекс не создан: «Индекс не создан, перейдите в Настройки RAG»
- RAG Chat: заглушка (retrieval не реализовывать)
- Стратегия по умолчанию: FIXED_SIZE
- chunk_size=500, overlap=50, top-K=4
- Метаданные: source, title, section, chunk_id
- Destructive migration (БД v10→v11): принято

## Задачи
| ID | Описание | Статус |
|----|----------|--------|
| A-01 | Room: таблицы rag_chunks + rag_vocabulary, DAO, миграция v10→v11 | TODO |
| A-02 | RagAssetLoader + интерфейс Chunker + FixedSizeChunker | TODO |
| A-03 | StructuralChunker | TODO |
| A-04 | TfIdfEmbedder + интерфейс Embedder | TODO |
| A-05 | RagIndexer (orchestrator pipeline) + RagRetriever | TODO |
| A-06 | RagRepository (фасад + SharedPreferences rag_prefs) | TODO |
| A-07 | Koin ragModule в AppModule.kt | TODO |
| A-08 | MainActivity: TabRow (Chat / RAG Chat) | TODO |
| A-09 | RagChatScreen + RagChatViewModel (заглушка) | TODO |
| A-10 | RagSettingsScreen + RagSettingsViewModel | TODO |
| A-11 | SettingsDialog: кнопка «Настройки RAG» | TODO |
| A-12 | Unit тесты: FixedSizeChunker, StructuralChunker, TfIdfEmbedder, FakeRagDao | TODO |
| A-13 | assets/database-concepts.txt скопировать в app/src/main/assets/ | TODO |
