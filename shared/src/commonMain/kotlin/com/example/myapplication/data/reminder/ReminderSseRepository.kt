package com.example.myapplication.data.reminder

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val SSE_PATH = "/sse"

class ReminderSseRepository(private val httpClient: HttpClient) {

    fun connect(baseUrl: String, apiKey: String): Flow<ReminderEvent> = flow {
        httpClient.sse(
            urlString = "$baseUrl$SSE_PATH",
            request = {
                headers.append(HttpHeaders.Accept, "text/event-stream")
                headers.append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        ) {
            incoming.collect { event ->
                when (event.event) {
                    "ping", "endpoint", null -> {
                        // heartbeat или endpoint — пропускаем
                    }
                    "message" -> {
                        val data = event.data ?: return@collect
                        parseReminderNotification(data)?.let { emit(it) }
                    }
                    else -> {
                        val data = event.data ?: return@collect
                        if (data.isNotBlank()) {
                            parseReminderNotification(data)?.let { emit(it) }
                        }
                    }
                }
            }
        }
    }
}
