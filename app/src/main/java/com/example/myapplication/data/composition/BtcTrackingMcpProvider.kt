package com.example.myapplication.data.composition

import com.example.myapplication.data.mcp.McpConnectionStatus
import com.example.myapplication.data.mcp.McpProviderFacade
import com.example.myapplication.data.mcp.McpTool
import org.json.JSONObject

private val TOOL = McpTool(
    name = "start_btc_tracking",
    description = "Активирует фоновую подписку на цену биткоина раз в минуту. " +
            "После вызова этого инструмента СРАЗУ используй FINAL_ANSWER — " +
            "НЕ жди данных и НЕ делай дополнительных вызовов. " +
            "Результаты будут появляться в чате автоматически каждую минуту.",
    inputSchemaJson = """{"type":"object","properties":{"user_request":{"type":"string","description":"Оригинальный запрос пользователя"}},"required":["user_request"]}"""
)

class BtcTrackingMcpProvider : McpProviderFacade {

    private var controller: BtcTrackingController? = null

    fun bind(controller: BtcTrackingController) {
        this.controller = controller
    }

    fun unbind() {
        controller = null
    }

    override val isEnabled: Boolean get() = true
    override val isConnected: Boolean get() = true

    override suspend fun connect(): McpConnectionStatus =
        McpConnectionStatus.Connected(listOf(TOOL))

    override fun disconnect() = Unit

    override suspend fun callTool(toolName: String, argumentsJson: String): String {
        if (toolName != "start_btc_tracking") return "Error: unknown tool $toolName"
        val userRequest = try {
            JSONObject(argumentsJson).optString("user_request", "")
        } catch (_: Exception) { "" }

        if (userRequest.isBlank()) return "Error: user_request is required"

        android.util.Log.d("BtcTrackingMcp", "start_btc_tracking: $userRequest")
        controller?.startBtcTracking(userRequest)
            ?: return "Error: controller not bound"

        return "ПОДПИСКА АКТИВИРОВАНА. Результаты будут появляться в чате каждую минуту автоматически. Используй FINAL_ANSWER прямо сейчас."
    }
}
