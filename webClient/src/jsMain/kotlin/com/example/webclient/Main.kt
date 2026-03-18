package com.example.webclient

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.di.jsModule
import com.example.myapplication.domain.repository.SessionRepository
import org.koin.core.context.startKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import react.create
import react.dom.client.createRoot
import web.dom.document

object App : KoinComponent {
    val sessionRepo: SessionRepository by inject()
    val agent: LLMAgent by inject()
}

fun main() {
    startKoin {
        modules(jsModule)
    }

    val container = document.getElementById("root")
        ?: error("Element with id 'root' not found")

    createRoot(container).render(AppRoot.create())
}
