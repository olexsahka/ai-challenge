# mcp-composition
Статус: 🔄 REWORK_1
Итерация Review: 1
Итерация Rework: 1

## Задачи
| ID | Описание | Статус |
|----|----------|--------|
| BE-01 | Backend plan: save-server — добавить list_files и read_file в SaveHandler | PLANNED |
| AN-01 | Android plan: CompositionFlowUseCase, ReminderForegroundService bridge, BtcCompositionSettings toggle, 5+ unit-тестов | PLANNED |

## План бэкенда
- Создан: `docs/plans/plan-backend-mcp-composition.md`
- BE-задач: 7 (BE-01 … BE-07)
- Новые tool-ы save-server: `list_files`, `read_file`
- Endpoint: `http://10.0.2.2:8083/mcp` (без изменений)
- Подход: TDD (RED → GREEN), минимум 8 новых unit-тестов в `SaveHandlerTest.kt`
- AC покрытие (backend-side): 8/8 — AC-03, AC-04, AC-05 (косвенно), AC-06, AC-07, AC-11.1, AC-11.2, AC-11.3

## План Android
- Создан: `docs/plans/plan-android-mcp-composition.md`
- SH-задач: 2 (SH-01 verify AgentRunner unchanged, SH-02 ActiveSessionProvider интерфейс)
- UI-задач: 8 (UI-01 BtcCompositionSettings, UI-02 промпт, UI-03 CompositionFlowUseCase, UI-04 Koin, UI-05 AgentViewModel, UI-06 ReminderForegroundService bridge, UI-07 AgentScreen toggle, UI-08 TDD тесты)
- Архитектурный выбор bridge SSE → ViewModel: **вариант (b)** — singleton `CompositionFlowUseCase`
- Новых тестов: минимум 5 (по AC-10), рекомендуется 7-8
- AC покрытие (Android-side): 11/11 (AC-11 — backend, см. plan-backend)

## Планировщики
- backend-planner: ✅ Создан docs/plans/plan-backend-mcp-composition.md
- android-planner: ✅ Создан docs/plans/plan-android-mcp-composition.md
- Порядок: ✅ Оба плана готовы. Android может стартовать параллельно с backend на задачах UI-01..UI-03+UI-08 (TDD с моками). Интеграционный прогон AC-04/AC-06 — после BE-07.

## Rework итерации

### REWORK_1 (2026-05-17)
Источник: docs/reviews/mcp-composition-review-1.md
Критичных замечаний: 1 (Android)
Важных замечаний: 3 (Android)
Backend: APPROVED (замечания неблокирующие)

Блокеры для исправления:
- [КРИТИЧНО] UI-08-FIX: `CompositionFlowUseCaseTest.kt` — AgentRunner создаётся без MCP-провайдеров. Исправить `makeUseCase()` → `AgentRunner(api, makeMemory(), crypto, save)`.
- [ВАЖНО] UI-03-FIX: Shared AgentRunner — загрязнение conversationHistory. Создать `single(named("composition")) { AgentRunner(...) }` в AppModule.kt и передать в CompositionFlowUseCase.
- [ВАЖНО] UI-08-FIX: Тест single-flight проверяет последовательные вызовы, не конкурентные. Переписать с `async { ... }` + `awaitAll(job1, job2)`.
- [ВАЖНО] UI-06-FIX: `Job()` вместо `SupervisorJob()` в `ReminderForegroundService.kt:29`.

Инструкции по исправлению: docs/plans/mcp-composition-android.md → раздел REWORK_1

## Источники
- ADR:  docs/adr/mcp-composition.md
- Spec: docs/specs/mcp-composition.md

## Открытые вопросы — ЗАКРЫТЫ (ответы пользователя зафиксированы в ADR)
- [x] Точное имя BTC tool на Crypto MCP сервере → `get_price` с `{"symbol":"BTC"}` (подтверждено из исходников `CryptoMcpServer/src/main/kotlin/org/example/cryptomcp/mcp/tools/GetPriceTool.kt`)
- [x] Триггер «каждую минуту» → существующий SSE-поток `ReminderForegroundService` (без WorkManager)
- [x] Куда выводить результат дельты → в чат `AgentScreen` через `agent.saveAssistantMessage(...)` (НЕ в notification)
- [x] Парсинг цены BTC из текста сводки → LLM сама извлекает число (default)
- [x] Конкурентность → single-flight через Mutex (default)

## Неблокирующие нюансы (на усмотрение planner-а)
- Что делать, если `_activeSessionId == null` в момент SSE-события → дефолт: skip + log
- Визуальный префикс `[BTC composition]` в сообщении чата — опционально
