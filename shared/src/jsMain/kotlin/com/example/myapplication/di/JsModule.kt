package com.example.myapplication.di

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.agent.AgentRunner
import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.api.KtorLLMApiClient
import com.example.myapplication.data.api.createHttpClient
import com.example.myapplication.data.repository.ConstraintsRepository
import com.example.myapplication.data.repository.UserProfileRepository
import com.example.myapplication.data.repository.js.JsBranchNodeRepository
import com.example.myapplication.data.repository.js.JsFactRepository
import com.example.myapplication.data.repository.js.JsMessageRepository
import com.example.myapplication.data.repository.js.JsSessionRepository
import com.example.myapplication.data.repository.js.JsSummaryRepository
import com.example.myapplication.data.repository.js.JsTaskFsmRepository
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.domain.repository.BranchNodeRepository
import com.example.myapplication.domain.repository.FactRepository
import com.example.myapplication.domain.repository.MessageRepository
import com.example.myapplication.domain.repository.SessionRepository
import com.example.myapplication.domain.repository.SummaryRepository
import com.example.myapplication.domain.repository.TaskFsmRepository
import com.example.myapplication.platform.AppDispatchers
import com.example.myapplication.platform.Clock
import com.example.myapplication.platform.DateFormatter
import com.example.myapplication.platform.KeyValueStorage
import com.example.myapplication.platform.Logger
import com.example.myapplication.platform.UuidGenerator
import com.example.myapplication.platform.js.JsClock
import com.example.myapplication.platform.js.JsDateFormatter
import com.example.myapplication.platform.js.JsDispatchers
import com.example.myapplication.platform.js.JsKeyValueStorage
import com.example.myapplication.platform.js.JsLogger
import com.example.myapplication.platform.js.JsUuidGenerator
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val BASE_URL = "https://api.proxyapi.ru/openai/v1"
// API key should be injected via environment / config in a real web app
private const val API_KEY = ""

val jsModule = module {
    // HTTP client & API
    single { createHttpClient() }
    single<LLMApiClient> { KtorLLMApiClient(get(), BASE_URL, API_KEY) }

    // Platform implementations
    single<Clock> { JsClock() }
    single<UuidGenerator> { JsUuidGenerator() }
    single<DateFormatter> { JsDateFormatter() }
    single<Logger> { JsLogger() }
    single<AppDispatchers> { JsDispatchers() }

    // Key-value storages (namespaced)
    single<KeyValueStorage>(named("memory")) { JsKeyValueStorage("memory_") }
    single<KeyValueStorage>(named("profile")) { JsKeyValueStorage("profile_") }
    single<KeyValueStorage>(named("constraints")) { JsKeyValueStorage("constraints_") }
    single<KeyValueStorage>(named("data")) { JsKeyValueStorage("data_") }

    // Repositories backed by localStorage
    single<SessionRepository> { JsSessionRepository(get(named("data"))) }
    single<MessageRepository> { JsMessageRepository(get(named("data"))) }
    single<SummaryRepository> { JsSummaryRepository(get(named("data"))) }
    single<FactRepository> { JsFactRepository(get(named("data"))) }
    single<BranchNodeRepository> { JsBranchNodeRepository(get(named("data"))) }
    single<TaskFsmRepository> { JsTaskFsmRepository(get(named("data"))) }

    // Agent helpers
    single { AgentMemory(get(named("memory"))) }
    single { UserProfileRepository(get(named("profile"))) }
    single { ConstraintsRepository(get(named("constraints"))) }

    // Core agent
    single {
        LLMAgent(
            api = get(),
            sessionRepo = get(),
            messageRepo = get(),
            memory = get(),
            summaryRepo = get(),
            factRepo = get(),
            branchNodeRepo = get(),
            userProfileRepository = get(),
            taskFsmRepository = get(),
            constraintsRepository = get(),
            clock = get(),
            uuidGenerator = get(),
            dateFormatter = get()
        )
    }

    // ReAct agent runner
    factory { AgentRunner(api = get(), memory = get()) }
}
