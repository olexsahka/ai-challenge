package com.example.myapplication.agent

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage
import com.example.myapplication.data.api.model.extractText
import com.example.myapplication.data.db.dao.BranchNodeDao
import com.example.myapplication.data.db.dao.FactDao
import com.example.myapplication.data.db.dao.MessageDao
import com.example.myapplication.data.db.dao.SessionDao
import com.example.myapplication.data.db.dao.SummaryDao
import com.example.myapplication.data.repository.ConstraintsRepository
import com.example.myapplication.data.repository.UserProfileRepository
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.data.db.entity.MessageEntity
import com.example.myapplication.data.db.entity.MemoryStrategy
import com.example.myapplication.data.db.entity.SessionContextConfig
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.data.db.entity.SummaryEntity
import com.example.myapplication.data.db.entity.TaskFsmEntity
import com.example.myapplication.data.db.entity.TaskStage
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.MessageMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LLMAgent(
    private val api: AnthropicApi,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val memory: AgentMemory,
    private val summaryDao: SummaryDao,
    private val factDao: FactDao,
    private val branchNodeDao: BranchNodeDao,
    private val userProfileRepository: UserProfileRepository,
    private val taskFsmRepository: TaskFsmRepository,
    private val constraintsRepository: ConstraintsRepository
) {
    private val titleFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    fun observeMessages(sessionId: String): Flow<List<Message>> =
        messageDao.observeBySession(sessionId).map { entities ->
            entities.map { e ->
                Message(
                    id = e.id,
                    content = e.content,
                    isFromUser = e.isFromUser,
                    meta = if (!e.isFromUser && e.model.isNotEmpty()) {
                        MessageMeta(
                            inputTokens = e.inputTokens,
                            outputTokens = e.outputTokens,
                            durationMs = e.durationMs,
                            model = e.model
                        )
                    } else null
                )
            }
        }

    suspend fun getOrRestoreLastSession(): SessionEntity? = sessionDao.getLatest()

    suspend fun createSession(): SessionEntity {
        val now = System.currentTimeMillis()
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            startedAt = now,
            title = titleFormat.format(Date(now))
        )
        sessionDao.insert(session)
        return session
    }

    suspend fun updateSessionContext(sessionId: String, config: SessionContextConfig) {
        sessionDao.updateContext(
            sessionId,
            config.systemPrompt,
            config.model,
            config.temperature,
            config.compressionEnabled,
            config.compressionN,
            config.compressionM,
            config.memoryStrategy,
            config.slidingWindowN,
            config.stickyFactsN
        )
    }

    fun observeFacts(sessionId: String): Flow<List<FactEntity>> =
        factDao.observeBySession(sessionId)

    fun observeBranchNodes(sessionId: String): Flow<List<BranchNodeEntity>> =
        branchNodeDao.observeBySession(sessionId)

    suspend fun getOrCreateRootNode(sessionId: String): BranchNodeEntity {
        val existing = branchNodeDao.getBySession(sessionId).firstOrNull()
        if (existing != null) return existing
        val root = BranchNodeEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            parentId = null,
            label = "Root"
        )
        branchNodeDao.insert(root)
        return root
    }

    suspend fun forkNode(sessionId: String, fromNodeId: String, label: String): BranchNodeEntity {
        val allNodes = branchNodeDao.getBySession(sessionId)
        val siblingCount = allNodes.count { it.parentId == fromNodeId }
        val newLabel = if (label.isBlank()) "Branch ${siblingCount + 1}" else label
        val node = BranchNodeEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            parentId = fromNodeId,
            label = newLabel
        )
        branchNodeDao.insert(node)
        return node
    }

    suspend fun renameNode(nodeId: String, label: String) {
        branchNodeDao.updateLabel(nodeId, label)
    }

    suspend fun sendMessageToNode(sessionId: String, nodeId: String, userText: String): Result<Message> {
        val session = sessionDao.getById(sessionId)
            ?: return Result.failure(Exception("Session not found"))

        val existingMessages = messageDao.getByNode(nodeId)
        val isFirstMessage = existingMessages.isEmpty()

        val userMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            content = userText,
            isFromUser = true,
            createdAt = System.currentTimeMillis(),
            branchNodeId = nodeId
        )
        messageDao.insert(userMsg)

        if (isFirstMessage) {
            val autoLabel = userText.split(Regex("(?<=[.!?])\\s+")).firstOrNull()
                ?.trim()?.take(40)
                ?: userText.take(40)
            branchNodeDao.updateLabel(nodeId, autoLabel)
        }

        return try {
            val instructions = buildInstructions(session)
            val history = buildBranchHistory(sessionId, nodeId)
            val request = ChatRequest(
                model = session.model,
                instructions = instructions.takeIf { it.isNotBlank() },
                input = history,
                temperature = if (session.temperature == 1.0f) null else session.temperature
            )
            val startMs = System.currentTimeMillis()
            val response = api.sendMessage(request)
            val durationMs = System.currentTimeMillis() - startMs

            val text = response.extractText()
                ?: return Result.failure(Exception("Empty response"))

            val assistantEntity = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                content = text,
                isFromUser = false,
                createdAt = System.currentTimeMillis(),
                inputTokens = response.usage?.input_tokens ?: 0,
                outputTokens = response.usage?.output_tokens ?: 0,
                durationMs = durationMs,
                model = session.model,
                branchNodeId = nodeId
            )
            messageDao.insert(assistantEntity)

            Result.success(
                Message(
                    id = assistantEntity.id,
                    content = text,
                    isFromUser = false,
                    meta = MessageMeta(
                        inputTokens = assistantEntity.inputTokens,
                        outputTokens = assistantEntity.outputTokens,
                        durationMs = durationMs,
                        model = session.model
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeNodeMessages(sessionId: String, nodeId: String): Flow<List<Message>> {
        // Collect ancestor chain node IDs (root first, current last)
        return branchNodeDao.observeBySession(sessionId).flatMapLatest { allNodes ->
            val nodeMap = allNodes.associateBy { it.id }
            val chain = mutableListOf<String>()
            var cursor: String? = nodeId
            while (cursor != null) {
                chain.add(0, cursor)
                cursor = nodeMap[cursor]?.parentId
            }
            // Observe each node's messages as separate flows, combine them in order
            if (chain.isEmpty()) {
                flowOf(emptyList())
            } else {
                val flows = chain.map { nId -> messageDao.observeByNode(nId) }
                combine(flows) { arrays -> arrays.flatMap { it.toList() } }
                    .map { entities ->
                        entities.map { e ->
                            Message(
                                id = e.id,
                                content = e.content,
                                isFromUser = e.isFromUser,
                                meta = if (!e.isFromUser && e.model.isNotEmpty()) MessageMeta(
                                    inputTokens = e.inputTokens,
                                    outputTokens = e.outputTokens,
                                    durationMs = e.durationMs,
                                    model = e.model
                                ) else null
                            )
                        }
                    }
            }
        }
    }

    private suspend fun buildBranchHistory(sessionId: String, nodeId: String): List<InputMessage> {
        val allNodes = branchNodeDao.getBySession(sessionId).associateBy { it.id }
        // Walk ancestor chain from current node up to root
        val chain = mutableListOf<String>()
        var cursor: String? = nodeId
        while (cursor != null) {
            chain.add(0, cursor)
            cursor = allNodes[cursor]?.parentId
        }
        val result = mutableListOf<InputMessage>()
        for (nId in chain) {
            val msgs = messageDao.getByNode(nId)
            result.addAll(msgs.map { e ->
                InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
            })
        }
        return result
    }

    suspend fun getSession(sessionId: String): SessionEntity? = sessionDao.getById(sessionId)

    fun observeSummary(sessionId: String): Flow<com.example.myapplication.data.db.entity.SummaryEntity?> =
        summaryDao.observeBySession(sessionId)

    fun observeTaskFsm(sessionId: String) = taskFsmRepository.observe(sessionId)

    suspend fun pauseTask(sessionId: String) = taskFsmRepository.pause(sessionId)

    suspend fun resumeTask(sessionId: String) = taskFsmRepository.resume(sessionId)

    suspend fun resetTask(sessionId: String) = taskFsmRepository.reset(sessionId)

    suspend fun enableAutoRun(sessionId: String) = taskFsmRepository.enableAutoRun(sessionId)

    suspend fun disableAutoRun(sessionId: String) = taskFsmRepository.disableAutoRun(sessionId)

    suspend fun initTaskFsm(sessionId: String) = taskFsmRepository.getOrCreate(sessionId)

    suspend fun sendMessageAutoRun(sessionId: String, userText: String): Result<Message> {
        val session = sessionDao.getById(sessionId)
            ?: return Result.failure(Exception("Session not found"))
        val userMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            content = userText,
            isFromUser = true,
            createdAt = System.currentTimeMillis()
        )
        messageDao.insert(userMsg)
        // Create fresh FSM with autoRun enabled
        val fsm = TaskFsmEntity(sessionId = sessionId, autoRun = true)
        taskFsmRepository.upsert(fsm)
        return runFsmStep(session, userText, fsm)
    }

    suspend fun continueFromCurrentStage(sessionId: String): Result<Message> {
        val session = sessionDao.getById(sessionId)
            ?: return Result.failure(Exception("Session not found"))
        val fsm = taskFsmRepository.get(sessionId)
            ?: return Result.failure(Exception("No active FSM"))
        return runFsmStep(session, "", fsm)
    }

    fun getMemories(): List<MemoryEntry> = memory.recallAll()

    fun forgetMemory(key: String) = memory.forget(key)

    fun forgetAllMemory() = memory.forgetAll()

    suspend fun sendMessage(sessionId: String, userText: String): Result<Message> {
        val session = sessionDao.getById(sessionId)
            ?: return Result.failure(Exception("Session not found"))

        val userMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            content = userText,
            isFromUser = true,
            createdAt = System.currentTimeMillis()
        )
        messageDao.insert(userMsg)

        var fsm = taskFsmRepository.get(sessionId)
        // Auto-create FSM on first message if task memory is enabled and FSM doesn't exist yet
        if (fsm == null && userProfileRepository.taskMemory.enabled) {
            fsm = taskFsmRepository.getOrCreate(sessionId)
        }
        if (fsm != null && !fsm.paused) {
            return runFsmStep(session, userText, fsm)
        }

        return try {
            val instructions = buildInstructions(session)
            val history = buildHistory(session)
            val request = ChatRequest(
                model = session.model,
                instructions = instructions.takeIf { it.isNotBlank() },
                input = history,
                temperature = if (session.temperature == 1.0f) null else session.temperature
            )
            val startMs = System.currentTimeMillis()
            val response = api.sendMessage(request)
            val durationMs = System.currentTimeMillis() - startMs

            val text = response.extractText()
                ?: return Result.failure(Exception("Empty response"))

            val assistantEntity = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                content = text,
                isFromUser = false,
                createdAt = System.currentTimeMillis(),
                inputTokens = response.usage?.input_tokens ?: 0,
                outputTokens = response.usage?.output_tokens ?: 0,
                durationMs = durationMs,
                model = session.model
            )
            messageDao.insert(assistantEntity)

            if (sessionDao.countAssistantMessages(sessionId) == 1) {
                updateSessionTitle(sessionId, text)
            }

            val strategy = runCatching { MemoryStrategy.valueOf(session.memoryStrategy) }.getOrDefault(MemoryStrategy.FULL)
            if (strategy == MemoryStrategy.STICKY_FACTS) {
                updateFacts(session, userText, text)
            }

            Result.success(
                Message(
                    id = assistantEntity.id,
                    content = text,
                    isFromUser = false,
                    meta = MessageMeta(
                        inputTokens = assistantEntity.inputTokens,
                        outputTokens = assistantEntity.outputTokens,
                        durationMs = durationMs,
                        model = session.model
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun askConstraintsChecker(session: SessionEntity, prompt: String): String? {
        val request = ChatRequest(
            model = session.model,
            instructions = null,
            input = listOf(InputMessage(role = "user", content = prompt)),
            temperature = null
        )
        val raw = runCatching { api.sendMessage(request) }.getOrNull()
            ?.extractText()?.trim() ?: return null
        return if (raw.startsWith("VIOLATION:", ignoreCase = true)) {
            raw.removePrefix("VIOLATION:").removePrefix("violation:").trim()
        } else null
    }

    /**
     * Checks if the userText violates any active constraints by asking the LLM.
     * Returns a non-null violation description string if violated, null if OK.
     */
    private suspend fun checkConstraintViolation(session: SessionEntity, userText: String): String? {
        val constraints = constraintsRepository.constraints
        if (!constraints.enabled || constraints.rules.isBlank() || userText.isBlank()) return null

        val prompt = """You are a constraints checker. Given the list of agent constraints and a user request, determine if the request violates any constraint.

Agent constraints:
${constraints.rules.trim()}

User request:
$userText

Reply with EXACTLY one of:
- "OK" if the request does not violate any constraint.
- "VIOLATION: <constraint description>" if it violates a constraint (replace <constraint description> with the specific rule violated).

Reply with nothing else."""

        return askConstraintsChecker(session, prompt)
    }

    /**
     * Saves a constraint violation error message, sets FSM to ERROR, and returns the error result.
     */
    private suspend fun handleConstraintViolation(
        session: SessionEntity,
        violationDesc: String,
        originalRequest: String
    ): Result<Message> {
        taskFsmRepository.setError(session.id)

        // Ask LLM to suggest an alternative request that does not violate constraints
        val constraints = constraintsRepository.constraints
        val alternativePrompt = """The user sent a request that violates an agent constraint.

Agent constraints:
${constraints.rules.trim()}

Violated constraint: $violationDesc

User's original request:
$originalRequest

Suggest a concrete alternative request that:
1. Achieves a similar goal to the original request.
2. Does NOT violate any of the agent constraints listed above.

Reply with only the suggested alternative request text, without any preamble."""

        val altRequest = ChatRequest(
            model = session.model,
            instructions = null,
            input = listOf(InputMessage(role = "user", content = alternativePrompt)),
            temperature = null
        )
        val alternativeSuggestion = runCatching { api.sendMessage(altRequest) }
            .getOrNull()
            ?.extractText()
            ?.trim()

        val content = buildString {
            appendLine("❌ Ошибка: действие нарушает ограничение «$violationDesc»")
            appendLine()
            appendLine("Варианты:")
            appendLine("1. Изменить ограничения, чтобы разрешить это действие.")
            if (!alternativeSuggestion.isNullOrBlank()) {
                appendLine("2. Использовать альтернативный запрос:")
                appendLine()
                appendLine(alternativeSuggestion)
            } else {
                appendLine("2. Предложить альтернативный запрос, не нарушающий ограничения.")
            }
        }.trim()

        val errorEntity = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            content = content,
            isFromUser = false,
            createdAt = System.currentTimeMillis()
        )
        messageDao.insert(errorEntity)
        return Result.success(Message(id = errorEntity.id, content = content, isFromUser = false))
    }

    /**
     * Executes the current FSM stage/step (one step at a time by default).
     * If autoRun is enabled, continues through all remaining steps automatically.
     * After each step, if not autoRun, appends a prompt asking the user to proceed.
     */
    private suspend fun runFsmStep(session: SessionEntity, userText: String, fsm: TaskFsmEntity): Result<Message> {
        val sessionId = session.id
        val taskMemory = userProfileRepository.taskMemory
        val autoRun = fsm.autoRun

        val currentStage = runCatching { TaskStage.valueOf(fsm.stage) }.getOrElse { TaskStage.PLANNING }

        // If already DONE, just do a normal message
        if (currentStage == TaskStage.DONE) {
            return sendNormalMessage(session, taskMemory)
        }

        // If ERROR — reset FSM and restart planning with the new message
        if (currentStage == TaskStage.ERROR) {
            taskFsmRepository.reset(sessionId)
            val freshFsm = taskFsmRepository.get(sessionId) ?: TaskFsmEntity(sessionId = sessionId, autoRun = fsm.autoRun)
            return runFsmStep(session, userText, freshFsm)
        }

        // Check constraints before any action (on initial user message)
        if (currentStage == TaskStage.PLANNING && userText.isNotBlank()) {
            val violation = checkConstraintViolation(session, userText)
            if (violation != null) {
                return handleConstraintViolation(session, violation, userText)
            }
        }

        // PLANNING stage: generate plan if not done yet
        if (currentStage == TaskStage.PLANNING) {
            taskFsmRepository.transitionTo(sessionId, TaskStage.PLANNING, 1, "generate_plan")
            val planResult = callApiForStage(session, taskMemory, stageLabel = "📋 Planning")
                ?: run { taskFsmRepository.setError(sessionId); return Result.failure(Exception("Planning stage failed")) }
            // Post-check: verify planning response does not violate constraints
            val planViolation = checkResponseViolation(session, planResult.text)
            if (planViolation != null) {
                saveAssistantMessage(session, planResult)
                return handleConstraintViolation(session, planViolation, userText)
            }
            saveAssistantMessage(session, planResult)
            if (sessionDao.countAssistantMessages(sessionId) == 1) updateSessionTitle(sessionId, planResult.text)

            val stepCount = planResult.text.lines()
                .count { it.trim().matches(Regex("^\\d+\\..*")) }

            // No numbered steps — model couldn't generate a plan (bad input)
            if (stepCount == 0) {
                // Mark planning response and user message as errors (exclude from history)
                val allMsgs = messageDao.getBySession(sessionId)
                allMsgs.takeLast(2).forEach { messageDao.markAsError(it.id) }
                taskFsmRepository.setError(sessionId)
                val errorEntity = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    content = "❌ Не удалось составить план. Пожалуйста, опишите задачу подробнее.",
                    isFromUser = false,
                    createdAt = System.currentTimeMillis(),
                    isError = true
                )
                messageDao.insert(errorEntity)
                return Result.success(Message(id = errorEntity.id, content = errorEntity.content, isFromUser = false))
            }

            // Transition to EXECUTION step 1, save stepCount
            taskFsmRepository.transitionTo(sessionId, TaskStage.EXECUTION, 1, "execute_step", stepCount)

            if (!autoRun) {
                // Ask user to confirm next step
                val promptEntity = savePromptMessage(session, "Готов к выполнению плана из $stepCount шагов. Приступить к шагу 1?")
                return Result.success(Message(id = promptEntity.id, content = promptEntity.content, isFromUser = false))
            }
            // autoRun: fall through to execute all steps
        }

        // EXECUTION stage
        var currentFsm = taskFsmRepository.get(sessionId) ?: fsm
        if (runCatching { TaskStage.valueOf(currentFsm.stage) }.getOrNull() == TaskStage.EXECUTION) {
            val stepCount = currentFsm.stepCount.coerceAtLeast(1)
            val startStep = currentFsm.step

            if (autoRun) {
                // Execute all remaining steps, stop if autoRun was disabled (Stop button)
                for (step in startStep..stepCount) {
                    currentFsm = taskFsmRepository.get(sessionId) ?: break
                    if (!currentFsm.autoRun) {
                        // User pressed Stop — stay at current step
                        taskFsmRepository.transitionTo(sessionId, TaskStage.EXECUTION, step, "execute_step", stepCount)
                        val promptEntity = savePromptMessage(session, "Авто-запуск остановлен на шаге $step/$stepCount. Продолжить?")
                        return Result.success(Message(id = promptEntity.id, content = promptEntity.content, isFromUser = false))
                    }
                    taskFsmRepository.transitionTo(sessionId, TaskStage.EXECUTION, step, "execute_step", stepCount)
                    val stepResult = callApiForStage(session, taskMemory, stageLabel = "⚙️ Шаг $step/$stepCount")
                        ?: run { taskFsmRepository.setError(sessionId); return Result.failure(Exception("Execution step $step failed")) }
                    // Post-check: verify step response does not violate constraints
                    val stepViolation = checkResponseViolation(session, stepResult.text)
                    if (stepViolation != null) {
                        saveAssistantMessage(session, stepResult)
                        return handleConstraintViolation(session, stepViolation, userText)
                    }
                    saveAssistantMessage(session, stepResult)
                }
            } else {
                // Execute only the current step
                taskFsmRepository.transitionTo(sessionId, TaskStage.EXECUTION, startStep, "execute_step", stepCount)
                val stepResult = callApiForStage(session, taskMemory, stageLabel = "⚙️ Шаг $startStep/$stepCount")
                    ?: run { taskFsmRepository.setError(sessionId); return Result.failure(Exception("Execution step $startStep failed")) }
                // Post-check: verify step response does not violate constraints
                val singleStepViolation = checkResponseViolation(session, stepResult.text)
                if (singleStepViolation != null) {
                    saveAssistantMessage(session, stepResult)
                    return handleConstraintViolation(session, singleStepViolation, userText)
                }
                saveAssistantMessage(session, stepResult)

                if (startStep < stepCount) {
                    taskFsmRepository.transitionTo(sessionId, TaskStage.EXECUTION, startStep + 1, "execute_step", stepCount)
                    val promptEntity = savePromptMessage(session, "Шаг $startStep выполнен. Приступить к шагу ${startStep + 1}/$stepCount?")
                    return Result.success(Message(id = promptEntity.id, content = promptEntity.content, isFromUser = false))
                }
                // Last step done — move to VALIDATION
            }

            // Check if autoRun was stopped after last step
            currentFsm = taskFsmRepository.get(sessionId) ?: fsm
            if (!currentFsm.autoRun && autoRun) {
                return Result.success(Message(id = UUID.randomUUID().toString(), content = "Авто-запуск остановлен.", isFromUser = false))
            }

            taskFsmRepository.transitionTo(sessionId, TaskStage.VALIDATION, 1, "validate_results")

            if (!autoRun) {
                val promptEntity = savePromptMessage(session, "Все шаги выполнены. Приступить к валидации?")
                return Result.success(Message(id = promptEntity.id, content = promptEntity.content, isFromUser = false))
            }
        }

        // VALIDATION stage
        currentFsm = taskFsmRepository.get(sessionId) ?: fsm
        if (runCatching { TaskStage.valueOf(currentFsm.stage) }.getOrNull() == TaskStage.VALIDATION) {
            taskFsmRepository.transitionTo(sessionId, TaskStage.VALIDATION, 1, "validate_results")
            val validationResult = callApiForStage(session, taskMemory, stageLabel = "✅ Валидация")
                ?: run { taskFsmRepository.setError(sessionId); return Result.failure(Exception("Validation stage failed")) }
            saveAssistantMessage(session, validationResult)

            val validationFailed = validationResult.text.lowercase().let {
                it.contains("validation failed") || it.contains("needs correction") || it.contains("error detected")
            }
            if (validationFailed) {
                taskFsmRepository.validationFailed(sessionId)
                val stepCount = currentFsm.stepCount.coerceAtLeast(1)
                taskFsmRepository.transitionTo(sessionId, TaskStage.EXECUTION, 1, "execute_step", stepCount)
                val retryResult = callApiForStage(session, taskMemory, stageLabel = "⚙️ Повторное выполнение")
                    ?: return Result.failure(Exception("Retry execution failed"))
                saveAssistantMessage(session, retryResult)
                taskFsmRepository.transitionTo(sessionId, TaskStage.VALIDATION, 1, "validate_results")
                val revalidation = callApiForStage(session, taskMemory, stageLabel = "✅ Повторная валидация")
                    ?: return Result.failure(Exception("Revalidation failed"))
                saveAssistantMessage(session, revalidation)
            }

            taskFsmRepository.markDone(sessionId)

            if (!autoRun) {
                val promptEntity = savePromptMessage(session, "Валидация пройдена. Завершить задачу?")
                return Result.success(Message(id = promptEntity.id, content = promptEntity.content, isFromUser = false))
            }
        }

        // DONE stage
        taskFsmRepository.markDone(sessionId)
        val doneResult = callApiForStage(session, taskMemory, stageLabel = "🏁 Готово")
            ?: return Result.failure(Exception("Done stage failed"))
        val doneEntity = saveAssistantMessage(session, doneResult)

        return Result.success(
            Message(
                id = doneEntity.id,
                content = doneResult.text,
                isFromUser = false,
                meta = MessageMeta(
                    inputTokens = doneResult.inputTokens,
                    outputTokens = doneResult.outputTokens,
                    durationMs = doneResult.durationMs,
                    model = session.model
                )
            )
        )
    }

    private suspend fun sendNormalMessage(session: SessionEntity, taskMemory: com.example.myapplication.data.repository.TaskMemory): Result<Message> {
        return try {
            val instructions = buildInstructions(session)
            val history = buildHistory(session)
            val request = ChatRequest(
                model = session.model,
                instructions = instructions.takeIf { it.isNotBlank() },
                input = history,
                temperature = if (session.temperature == 1.0f) null else session.temperature
            )
            val startMs = System.currentTimeMillis()
            val response = api.sendMessage(request)
            val durationMs = System.currentTimeMillis() - startMs
            val text = response.extractText() ?: return Result.failure(Exception("Empty response"))
            val entity = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                content = text,
                isFromUser = false,
                createdAt = System.currentTimeMillis(),
                inputTokens = response.usage?.input_tokens ?: 0,
                outputTokens = response.usage?.output_tokens ?: 0,
                durationMs = durationMs,
                model = session.model
            )
            messageDao.insert(entity)
            Result.success(Message(id = entity.id, content = text, isFromUser = false, meta = MessageMeta(entity.inputTokens, entity.outputTokens, durationMs, session.model)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks if a model response text violates any active constraints.
     * Returns a non-null violation description if violated, null if OK.
     */
    private suspend fun checkResponseViolation(session: SessionEntity, responseText: String): String? {
        val constraints = constraintsRepository.constraints
        if (!constraints.enabled || constraints.rules.isBlank()) return null

        val prompt = """You are a constraints checker. Given the list of agent constraints and an agent response, determine if the response violates any constraint.

Agent constraints:
${constraints.rules.trim()}

Agent response:
$responseText

Reply with EXACTLY one of:
- "OK" if the response does not violate any constraint.
- "VIOLATION: <constraint description>" if it violates a constraint.

Reply with nothing else."""

        return askConstraintsChecker(session, prompt)
    }

    private suspend fun savePromptMessage(session: SessionEntity, text: String): MessageEntity {
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            content = text,
            isFromUser = false,
            createdAt = System.currentTimeMillis()
        )
        messageDao.insert(entity)
        return entity
    }

    private data class StageResponse(
        val text: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val durationMs: Long
    )

    private suspend fun callApiForStage(
        session: SessionEntity,
        taskMemory: com.example.myapplication.data.repository.TaskMemory,
        stageLabel: String
    ): StageResponse? {
        val fsm = taskFsmRepository.get(session.id) ?: return null
        val instructions = buildInstructionsWithFsm(session, fsm, taskMemory)
        val history = buildHistory(session)
        val request = ChatRequest(
            model = session.model,
            instructions = instructions.takeIf { it.isNotBlank() },
            input = history,
            temperature = if (session.temperature == 1.0f) null else session.temperature
        )
        return try {
            val startMs = System.currentTimeMillis()
            val response = api.sendMessage(request)
            val durationMs = System.currentTimeMillis() - startMs
            val raw = response.extractText() ?: return null
            val text = "**$stageLabel**\n\n$raw"
            StageResponse(
                text = text,
                inputTokens = response.usage?.input_tokens ?: 0,
                outputTokens = response.usage?.output_tokens ?: 0,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun saveAssistantMessage(session: SessionEntity, result: StageResponse): MessageEntity {
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            content = result.text,
            isFromUser = false,
            createdAt = System.currentTimeMillis(),
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            durationMs = result.durationMs,
            model = session.model
        )
        messageDao.insert(entity)
        return entity
    }

    private suspend fun updateSessionTitle(sessionId: String, text: String) {
        val autoTitle = text.split(Regex("(?<=[.!?])\\s+")).firstOrNull()
            ?.trim()?.take(50) ?: text.take(50)
        sessionDao.updateTitle(sessionId, autoTitle)
    }

    private fun buildInstructionsWithFsm(
        session: SessionEntity,
        fsm: com.example.myapplication.data.db.entity.TaskFsmEntity,
        taskMemory: com.example.myapplication.data.repository.TaskMemory
    ): String {
        val parts = mutableListOf<String>()
        if (session.systemPrompt.isNotBlank()) parts.add(session.systemPrompt)
        val memCtx = memory.toContextString()
        if (memCtx.isNotBlank()) parts.add(memCtx)
        val activeConstraints = constraintsRepository.constraints.takeIf { it.enabled }
        parts.add(taskFsmRepository.toInstructionsBlock(fsm, taskMemory.takeIf { it.enabled }, activeConstraints))
        val userInfoCtx = userProfileRepository.userInformationContextString()
        if (userInfoCtx.isNotBlank()) parts.add(userInfoCtx)
        return parts.joinToString("\n\n")
    }

    private suspend fun buildInstructions(session: SessionEntity): String {
        val parts = mutableListOf<String>()
        if (session.systemPrompt.isNotBlank()) parts.add(session.systemPrompt)
        val memCtx = memory.toContextString()
        if (memCtx.isNotBlank()) parts.add(memCtx)
        val fsm = taskFsmRepository.get(session.id)
        val taskMemory = userProfileRepository.taskMemory
        if (fsm != null) {
            // FSM block absorbs task memory — no need to duplicate it via UserProfileRepository
            parts.add(taskFsmRepository.toInstructionsBlock(fsm, taskMemory.takeIf { it.enabled }))
            // Still include user information (non-task part)
            val userInfoCtx = userProfileRepository.userInformationContextString()
            if (userInfoCtx.isNotBlank()) parts.add(userInfoCtx)
        } else {
            val profileCtx = userProfileRepository.toContextString()
            if (profileCtx.isNotBlank()) parts.add(profileCtx)
        }
        return parts.joinToString("\n\n")
    }

    private suspend fun buildHistory(session: SessionEntity): List<InputMessage> {
        val all = messageDao.observeBySession(session.id).first().filter { !it.isError }

        val strategy = runCatching { MemoryStrategy.valueOf(session.memoryStrategy) }.getOrDefault(MemoryStrategy.FULL)
        if (strategy == MemoryStrategy.SLIDING_WINDOW) {
            return all.takeLast(session.slidingWindowN).map { e ->
                InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
            }
        }
        if (strategy == MemoryStrategy.STICKY_FACTS) {
            val facts = factDao.getBySession(session.id)
            val result = mutableListOf<InputMessage>()
            if (facts.isNotEmpty()) {
                val factsText = facts.joinToString("\n") { "- ${it.factKey}: ${it.factValue}" }
                result.add(InputMessage(role = "user", content = "[Known facts about this conversation]\n$factsText"))
                result.add(InputMessage(role = "assistant", content = "Understood, I have the known facts."))
            }
            result.addAll(all.takeLast(session.stickyFactsN).map { e ->
                InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
            })
            return result
        }

        if (strategy != MemoryStrategy.COMPRESSION || all.size <= session.compressionN) {
            return all.map { e ->
                InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
            }
        }

        val recent = all.takeLast(session.compressionN)
        val older = all.dropLast(session.compressionN)

        val summary = getOrUpdateSummary(session, older)

        val result = mutableListOf<InputMessage>()
        result.add(InputMessage(role = "user", content = "[Summary of earlier conversation]\n$summary"))
        result.add(InputMessage(role = "assistant", content = "Understood, I have the context from the earlier conversation."))
        result.addAll(recent.map { e ->
            InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
        })
        return result
    }

    private suspend fun updateFacts(session: SessionEntity, userText: String, assistantText: String) {
        val existing = factDao.getBySession(session.id)
        val existingFactsBlock = if (existing.isNotEmpty())
            "Current facts:\n" + existing.joinToString("\n") { "- ${it.factKey}: ${it.factValue}" }
        else
            "No facts stored yet."

        val prompt = """You are a fact extractor. Given the existing facts and the latest exchange, return an updated list of key facts.

$existingFactsBlock

Latest exchange:
User: $userText
Assistant: $assistantText

Return ONLY a list of facts in this exact format, one per line:
key: value

Extract facts about: goals, constraints, preferences, decisions, agreements, names, dates, or any important information. Keep existing facts that are still valid. Update or remove facts that have changed."""

        val request = ChatRequest(
            model = session.model,
            instructions = null,
            input = listOf(InputMessage(role = "user", content = prompt)),
            temperature = null
        )
        val raw = runCatching { api.sendMessage(request) }.getOrNull()
            ?.extractText() ?: return

        val newFacts = raw.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().trimStart('-').trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotBlank() && value.isNotBlank()) FactEntity(
                        sessionId = session.id,
                        factKey = key,
                        factValue = value
                    ) else null
                } else null
            }

        if (newFacts.isNotEmpty()) {
            factDao.deleteBySession(session.id)
            factDao.upsertAll(newFacts)
        }
    }

    private suspend fun getOrUpdateSummary(session: SessionEntity, olderMessages: List<MessageEntity>): String {
        val olderCount = olderMessages.size
        val existing = summaryDao.getBySession(session.id)

        if (existing != null && olderCount - existing.coveredMessageCount < session.compressionM) {
            return existing.summary
        }

        val historyText = olderMessages.joinToString("\n") { e ->
            val role = if (e.isFromUser) "User" else "Assistant"
            "$role: ${e.content}"
        }
        val summaryRequest = ChatRequest(
            model = session.model,
            instructions = "You are a conversation summarizer. Produce a concise summary of the conversation provided, capturing key facts, decisions, and context. Reply with only the summary text.",
            input = listOf(InputMessage(role = "user", content = "Summarize this conversation:\n\n$historyText")),
            temperature = null
        )
        val summary = api.sendMessage(summaryRequest).extractText()
            ?: "No summary available."

        summaryDao.upsert(
            SummaryEntity(
                sessionId = session.id,
                summary = summary,
                coveredMessageCount = olderCount
            )
        )
        return summary
    }

    suspend fun saveUserMessage(sessionId: String, text: String, branchNodeId: String? = null) {
        messageDao.insert(
            MessageEntity(
                id = java.util.UUID.randomUUID().toString(),
                sessionId = sessionId,
                content = text,
                isFromUser = true,
                branchNodeId = branchNodeId,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveAssistantMessage(sessionId: String, text: String, branchNodeId: String? = null) {
        messageDao.insert(
            MessageEntity(
                id = java.util.UUID.randomUUID().toString(),
                sessionId = sessionId,
                content = text,
                isFromUser = false,
                branchNodeId = branchNodeId,
                createdAt = System.currentTimeMillis()
            )
        )
        val session = sessionDao.getById(sessionId) ?: return
        if (session.title == "New Chat") {
            val firstSentence = text.split(Regex("[.!?\\n]")).firstOrNull { it.isNotBlank() }?.trim()
            if (!firstSentence.isNullOrBlank()) {
                sessionDao.updateTitle(sessionId, firstSentence.take(60))
            }
        }
    }
}
