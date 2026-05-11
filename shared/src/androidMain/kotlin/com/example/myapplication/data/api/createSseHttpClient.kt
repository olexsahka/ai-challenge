package com.example.myapplication.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import java.util.concurrent.TimeUnit

fun createSseHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(SSE)
    engine {
        config {
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.MILLISECONDS) // бесконечный — SSE стрим
            writeTimeout(30, TimeUnit.SECONDS)
        }
    }
}
