package com.example.myapplication.di

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.agent.AgentRunner
import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.agent.TaskFsmRepository
import com.example.myapplication.domain.repository.TaskFsmRepository as TaskFsmRepositoryInterface
import com.example.myapplication.data.api.KtorLLMApiClient
import com.example.myapplication.data.api.createHttpClient
import com.example.myapplication.data.mcp.McpClient
import com.example.myapplication.data.mcp.McpRepository
import com.example.myapplication.data.mcp.TelegramMcpClient
import com.example.myapplication.data.mcp.TelegramMcpRepository
import com.example.myapplication.data.mcp.StatelessMcpClient
import com.example.myapplication.data.mcp.StatelessMcpRepository
import com.example.myapplication.data.api.createSseHttpClient
import com.example.myapplication.data.reminder.CryptoMcpRepository
import com.example.myapplication.data.reminder.ReminderManager
import com.example.myapplication.data.reminder.ReminderSseRepository
import com.example.myapplication.data.db.AppDatabase
import com.example.myapplication.data.repository.ChatRepositoryImpl
import com.example.myapplication.data.repository.SettingsRepository
import com.example.myapplication.data.repository.SettingsRepositoryImpl
import com.example.myapplication.data.repository.ConstraintsRepository
import com.example.myapplication.data.repository.UserProfileRepository
import com.example.myapplication.data.repository.room.RoomBranchNodeRepository
import com.example.myapplication.data.repository.room.RoomFactRepository
import com.example.myapplication.data.repository.room.RoomMessageRepository
import com.example.myapplication.data.repository.room.RoomSessionRepository
import com.example.myapplication.data.repository.room.RoomSummaryRepository
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.domain.repository.BranchNodeRepository
import com.example.myapplication.domain.repository.ChatRepository
import com.example.myapplication.domain.repository.FactRepository
import com.example.myapplication.domain.repository.MessageRepository
import com.example.myapplication.domain.repository.SessionRepository
import com.example.myapplication.domain.repository.SummaryRepository
import com.example.myapplication.domain.usecase.SendMessageUseCase
import com.example.myapplication.platform.Clock
import com.example.myapplication.platform.DateFormatter
import com.example.myapplication.platform.KeyValueStorage
import com.example.myapplication.platform.UuidGenerator
import com.example.myapplication.platform.android.AndroidClock
import com.example.myapplication.platform.android.AndroidDateFormatter
import com.example.myapplication.platform.android.AndroidUuidGenerator
import com.example.myapplication.platform.android.SharedPrefsKeyValueStorage
import com.example.myapplication.data.composition.BtcCompositionSettings
import com.example.myapplication.data.composition.BtcTrackingMcpProvider
import com.example.myapplication.presentation.agent.AgentViewModel
import com.example.myapplication.presentation.chat.ChatViewModel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import com.example.myapplication.BuildConfig
import org.koin.dsl.module

private const val BASE_URL = "https://api.proxyapi.ru/openai/v1"

val appModule = module {

    // Ktor HTTP client for LLM API
    single { createHttpClient() }
    single<LLMApiClient> { KtorLLMApiClient(get(), BASE_URL, BuildConfig.PROXY_API_KEY) }

    single<ChatRepository> { ChatRepositoryImpl(get()) }

    single<SettingsRepository> { SettingsRepositoryImpl(androidContext()) }

    factory { SendMessageUseCase(get()) }

    // Platform implementations
    single<Clock> { AndroidClock() }
    single<UuidGenerator> { AndroidUuidGenerator() }
    single<DateFormatter> { AndroidDateFormatter() }
    single<KeyValueStorage>(named("memory")) { SharedPrefsKeyValueStorage(androidContext(), "agent_memory") }
    single<KeyValueStorage>(named("profile")) { SharedPrefsKeyValueStorage(androidContext(), "user_profile") }
    single<KeyValueStorage>(named("constraints")) { SharedPrefsKeyValueStorage(androidContext(), "agent_constraints") }

    single { AppDatabase.create(androidContext()) }
    single { get<AppDatabase>().sessionDao() }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().summaryDao() }
    single { get<AppDatabase>().factDao() }
    single { get<AppDatabase>().branchNodeDao() }
    single { get<AppDatabase>().taskFsmDao() }

    // Domain repository implementations
    single<SessionRepository> { RoomSessionRepository(get()) }
    single<MessageRepository> { RoomMessageRepository(get()) }
    single<SummaryRepository> { RoomSummaryRepository(get()) }
    single<FactRepository> { RoomFactRepository(get()) }
    single<BranchNodeRepository> { RoomBranchNodeRepository(get()) }

    single { AgentMemory(get(named("memory"))) }
    single { UserProfileRepository(get(named("profile"))) }
    single { ConstraintsRepository(get(named("constraints"))) }
    single<TaskFsmRepositoryInterface> { TaskFsmRepository(get()) }
    single { LLMAgent(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    // OkHttp client for MCP servers (plain, no auth interceptor)
    single(qualifier = named("plain")) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    single { McpClient(get(qualifier = named("plain"))) }
    single { McpRepository(androidContext(), get()) }
    single { TelegramMcpClient(get(qualifier = named("plain"))) }
    single { TelegramMcpRepository(androidContext(), get(), com.example.myapplication.BuildConfig.TELEGRAM_MCP_PASSWORD) }
    // Reminder SSE
    single(qualifier = named("sse")) { createSseHttpClient() }
    single { ReminderSseRepository(get(qualifier = named("sse"))) }
    single { ReminderManager(androidContext(), get(), get(qualifier = named("plain"))) }
    single { CryptoMcpRepository(androidContext(), get(qualifier = named("plain"))) }

    // Task MCP servers (composition demo: search → summarize → save)
    single(named("taskSearchClient"))    { StatelessMcpClient(get(qualifier = named("plain")), "http://10.0.2.2:8081/mcp") }
    single(named("taskSummarizeClient")) { StatelessMcpClient(get(qualifier = named("plain")), "http://10.0.2.2:8082/mcp") }
    single(named("taskSaveClient"))      { StatelessMcpClient(get(qualifier = named("plain")), "http://10.0.2.2:8083/mcp") }
    single(named("taskSearch"))    { StatelessMcpRepository(androidContext(), get(named("taskSearchClient")), "task_search_enabled") }
    single(named("taskSummarize")) { StatelessMcpRepository(androidContext(), get(named("taskSummarizeClient")), "task_summarize_enabled") }
    single(named("taskSave"))      { StatelessMcpRepository(androidContext(), get(named("taskSaveClient")), "task_save_enabled") }

    single { BtcCompositionSettings(androidContext()) }
    single { BtcTrackingMcpProvider() }

    single { AgentRunner(
        get<LLMApiClient>(),
        get(),
        get<McpRepository>(),
        get<TelegramMcpRepository>(),
        get<CryptoMcpRepository>(),
        get<StatelessMcpRepository>(named("taskSearch")),
        get<StatelessMcpRepository>(named("taskSummarize")),
        get<StatelessMcpRepository>(named("taskSave")),
        get<BtcTrackingMcpProvider>()
    ) }

    viewModel { ChatViewModel(get(), get(), get(), get()) }
    viewModel { AgentViewModel(get(), get(), get(), get(), get(), get(), get<ReminderManager>(), get<CryptoMcpRepository>(), get<StatelessMcpRepository>(named("taskSearch")), get<StatelessMcpRepository>(named("taskSummarize")), get<StatelessMcpRepository>(named("taskSave")), get(), get<BtcTrackingMcpProvider>()) }
}
