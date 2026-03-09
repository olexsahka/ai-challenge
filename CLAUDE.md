# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build and install on connected device
./gradlew installDebug

# Run all unit tests (baseline — должно быть 86 тестов, 0 failures)
./gradlew :app:testDebugUnitTest

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean assembleDebug
```

## Architecture

Single-module Android app (`app/`) with MVVM + Clean Architecture layers:

```
presentation/  →  agent/  →  data/
```

### Key packages

| Package | Purpose |
|---|---|
| `presentation/agent/` | `AgentScreen`, `AgentViewModel`, `AgentUiState` — primary chat UI |
| `presentation/chat/` | `ChatScreen`, `ChatViewModel` — legacy/alternative chat UI |
| `agent/` | `LLMAgent` (core request logic), `AgentMemory` (SharedPreferences KV store), `AgentRunner` (ReAct loop agent) |
| `data/api/` | `AnthropicApi` (Retrofit interface to OpenAI-compatible proxy) |
| `data/db/` | Room database: DAOs + entities for sessions, messages, summaries, facts, branch nodes |
| `data/repository/UserProfileRepository.kt` | SharedPreferences store for User Profile and Task Memory settings; `toContextString()` appends them to API instructions when enabled |
| `domain/` | `Message`, `Settings` models; `ChatRepository` interface; `SendMessageUseCase` |
| `di/AppModule.kt` | Koin DI — all singletons and viewmodels wired here |

### Memory strategies (`MemoryStrategy` enum in `SessionEntity`)

Each session uses one of five strategies, selected per-session in context settings:

- `FULL` — send entire message history
- `SLIDING_WINDOW` — send last N messages (`slidingWindowN`)
- `STICKY_FACTS` — extract key facts via extra API call after each exchange; send facts + last N messages (`stickyFactsN`)
- `COMPRESSION` — summarize older messages when threshold crossed; send summary + last N (`compressionN`/`compressionM`)
- `BRANCHING` — tree-structured conversation via `BranchNodeEntity`; history built by walking ancestor chain to root

### Branching conversation

`BranchNodeEntity` forms a tree (parentId FK). `LLMAgent.buildBranchHistory()` walks from a node up to root, concatenating all ancestor messages in order. Messages are scoped per node via `branchNodeId`.

### `AgentRunner` (ReAct loop)

Standalone ReAct-style agent (max 6 iterations) used independently from `LLMAgent`. Tools: `SEARCH_MEMORY`, `STORE_MEMORY`, `CALCULATE`, `FINAL_ANSWER`. Parses `THOUGHT/ACTION/INPUT` lines from model output.

## Key Constraints

- **API key hardcoded** in `di/AppModule.kt`. Backend is an OpenAI-compatible proxy at `https://api.proxyapi.ru/openai/v1/`.
- **Room uses destructive migration** — schema changes wipe existing data.
- **`AgentMemory` (SharedPreferences) is global** — shared across all sessions.
- **`UserProfileRepository`** stores User Profile and Task Memory globally (SharedPreferences). When enabled via toggles, their content is appended to every request's instructions by `LLMAgent.buildInstructions()`.
- `STICKY_FACTS` and `COMPRESSION` strategies make an extra API call synchronously within `sendMessage`, adding latency.
- Session title is auto-set from the first sentence of the first assistant response.
- **`jvmTarget = "11"`** — повышен с 1.8 для совместимости с mockito-kotlin тестами.

## Testing

Baseline тесты (Фаза 0 KMP-миграции) — 86 unit тестов, 0 failures.

```
app/src/test/java/com/example/myapplication/
├── AgentRunnerTest.kt           — ReAct loop (16 тестов)
├── BuildHistoryTest.kt          — 5 стратегий памяти (12 тестов)
├── BuildInstructionsTest.kt     — buildInstructions логика (11 тестов)
├── SendMessageTest.kt           — sendMessage full flow (11 тестов)
├── BuildBranchHistoryTest.kt    — branching history (9 тестов)
├── AgentMemoryTest.kt           — KV store (10 тестов)
├── UserProfileRepositoryTest.kt — profile/task context (11 тестов)
└── LLMAgentTestBase.kt          — Fake DAO инфраструктура (без тестов)
```

**Тестовая инфраструктура:** вместо реальных DAO используются `FakeSessionDao`, `FakeMessageDao`, `FakeSummaryDao`, `FakeFactDao`, `FakeBranchNodeDao` (in-memory, без Room/Android). `AgentMemory` и `UserProfileRepository` мокируются через Mockito (изолируют `Context`/`SharedPreferences`).

**Важно для будущих тестов:** `CapturingAnthropicApi.lastRequest` перезаписывается на каждый API вызов. Для стратегий с двумя вызовами (STICKY_FACTS, COMPRESSION) использовать `SequentialAnthropicApi.requests.first()` чтобы получить именно главный запрос.

## KMP Migration

Проект в процессе подготовки к Kotlin Multiplatform. План: [`KMP_MIGRATION_PLAN.md`](KMP_MIGRATION_PLAN.md).

**Текущий статус:** Фаза 0 завершена. Следующий шаг — Фаза 1 (выделение domain слоя, введение интерфейсов репозиториев).
