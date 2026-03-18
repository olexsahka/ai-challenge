package com.example.myapplication.platform.js

import com.example.myapplication.platform.UuidGenerator
import kotlin.random.Random

class JsUuidGenerator : UuidGenerator {
    override fun generate(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
    }
}
