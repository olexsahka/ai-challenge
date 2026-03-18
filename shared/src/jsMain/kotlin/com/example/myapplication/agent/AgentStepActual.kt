package com.example.myapplication.agent

import kotlin.random.Random

actual fun generateStepId(): String {
    val bytes = Random.nextBytes(16)
    return bytes.joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
}
