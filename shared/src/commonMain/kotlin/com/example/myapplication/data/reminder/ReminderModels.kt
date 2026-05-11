package com.example.myapplication.data.reminder

data class ReminderEvent(
    val type: String,           // PRICE_ALERT | PERIODIC | ONCE
    val symbol: String,
    val price: String,
    val message: String,
    val subscriptionId: String
)

sealed class ReminderConnectionStatus {
    object Disconnected : ReminderConnectionStatus()
    object Connecting : ReminderConnectionStatus()
    object Connected : ReminderConnectionStatus()
    data class Error(val message: String) : ReminderConnectionStatus()
}
