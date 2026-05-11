package com.example.myapplication.data.reminder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val reminderJson = Json { ignoreUnknownKeys = true }

internal fun parseReminderNotification(data: String): ReminderEvent? {
    return try {
        val root = reminderJson.parseToJsonElement(data).jsonObject
        val method = root["method"]?.jsonPrimitive?.contentOrNull
        if (method == "notifications/message") {
            val params = root["params"]?.jsonObject ?: return null
            val eventData = params["data"]?.jsonObject ?: return null
            ReminderEvent(
                type = eventData["type"]?.jsonPrimitive?.contentOrNull ?: "",
                symbol = eventData["symbol"]?.jsonPrimitive?.contentOrNull ?: "",
                price = eventData["price"]?.jsonPrimitive?.contentOrNull ?: "",
                message = eventData["message"]?.jsonPrimitive?.contentOrNull ?: "",
                subscriptionId = eventData["subscriptionId"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        } else null
    } catch (_: Exception) {
        null
    }
}
