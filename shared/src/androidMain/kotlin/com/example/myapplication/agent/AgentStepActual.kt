package com.example.myapplication.agent

actual fun generateStepId(): String = java.util.UUID.randomUUID().toString()
