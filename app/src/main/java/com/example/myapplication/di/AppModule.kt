package com.example.myapplication.di

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.repository.ChatRepositoryImpl
import com.example.myapplication.domain.repository.ChatRepository
import com.example.myapplication.domain.usecase.SendMessageUseCase
import com.example.myapplication.presentation.chat.ChatViewModel
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val BASE_URL = "https://api.proxyapi.ru/openai/v1/"
private const val API_KEY = "sk-R6pPAIxxB5hBJx0IBBAuxX0w5WQzpRVk"

val appModule = module {

    single {
        OkHttpClient.Builder()
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
//2026-02-18 02:07:12.911  6736-6882  okhttp.OkHttpClient     com.example.myapplication            I  <-- HTTP FAILED: java.net.SocketTimeoutException: timeout
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
