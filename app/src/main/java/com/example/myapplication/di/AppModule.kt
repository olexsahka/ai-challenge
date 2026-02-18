package com.example.myapplication.di

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.repository.ChatRepositoryImpl
import com.example.myapplication.domain.repository.ChatRepository
import com.example.myapplication.domain.usecase.SendMessageUseCase
import com.example.myapplication.presentation.chat.ChatViewModel
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val BASE_URL = "https://api.proxyapi.ru/openai/v1/"
private const val API_KEY = "sk-R6pPAIxxB5hBJx0IBBAuxX0w5WQzpRVk"

val appModule = module {

    single {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
    }
    single {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(get())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    single<AnthropicApi> { get<Retrofit>().create(AnthropicApi::class.java) }

    single<ChatRepository> { ChatRepositoryImpl(get()) }

    factory { SendMessageUseCase(get()) }

    viewModel { ChatViewModel(get()) }
}
