package com.example.myapplication.agent

import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage
import com.example.myapplication.data.api.model.extractText
import com.example.myapplication.data.mcp.McpConnectionStatus
import com.example.myapplication.data.mcp.McpProviderFacade
import com.example.myapplication.data.mcp.McpTool
import com.example.myapplication.domain.api.LLMApiClient

private const val MAX_ITERATIONS = 6
private const val MODEL = "gpt-4o-mini"

private val BASE_SYSTEM_PROMPT = """
You are an autonomous AI agent with memory and planning capabilities.
You operate in a loop: Think → Act → Observe → Remember → Repeat until done.

You have access to these tools (respond with EXACTLY this format):
THOUGHT: <your internal reasoning>
ACTION: <one of: SEARCH_MEMORY, STORE_MEMORY, CALCULATE, FINAL_ANSWER{MCP_ACTIONS}>
INPUT: <input for the action>

Tool descriptions:
- SEARCH_MEMORY: Retrieve a stored memory by key. INPUT = key name
- STORE_MEMORY: Save a fact. INPUT = key=value
- CALCULATE: Evaluate a math expression. INPUT = expression
- FINAL_ANSWER: Provide the final answer to the user. INPUT = your answer
{MCP_TOOL_DESCRIPTIONS}
After each action you will receive an OBSERVATION. Continue until you use FINAL_ANSWER.
Only output one step at a time.
When the user asks what you can do, list all available tools with their purpose in the user's language.

Telegram-specific notes (apply when Telegram tools are available):
- dialog_id in send_message and get_last_messages accepts the dialog TITLE (string) — you do NOT need to look up numeric IDs. Just pass the title you got from get_dialogs or search_dialog.
- "Избранное" / "Saved Messages" / "себе" / "myself": call get_dialogs first, find the PRIVATE dialog whose title is the account owner name, then use that title as dialog_id.
- search_dialog does NOT return Saved Messages — always use get_dialogs to find self-chat.
- Never show numeric chat IDs to the user.
- Call send_message directly without asking for confirmation — just send the message.
""".trimIndent()

/**
 * Parses tool input schema JSON string to extract field names and types.
 * Uses simple string parsing to stay KMP-safe (no org.json in commonMain).
 */
private fun buildToolDescription(tool: McpTool): String {
    val schemaJson = tool.inputSchemaJson
    // Extract "properties" object content with simple regex-like parsing
    val propsStart = schemaJson.indexOf("\"properties\"")
    if (propsStart < 0) return "- ${tool.name}: ${tool.description}. INPUT = {}"

    val braceStart = schemaJson.indexOf('{', propsStart + 12)
    if (braceStart < 0) return "- ${tool.name}: ${tool.description}. INPUT = {}"

    // Find matching closing brace
    var depth = 0
    var braceEnd = braceStart
    for (i in braceStart until schemaJson.length) {
        when (schemaJson[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) { braceEnd = i; break } }
        }
    }
    val propsContent = schemaJson.substring(braceStart + 1, braceEnd)

    // Extract top-level keys and their types
    val fields = mutableListOf<String>()
    val keyRegex = Regex("\"(\\w+)\"\\s*:\\s*\\{([^{}]*)\\}")
    for (match in keyRegex.findAll(propsContent)) {
        val fieldName = match.groupValues[1]
        val fieldBody = match.groupValues[2]
        val typeMatch = Regex("\"type\"\\s*:\\s*\"(\\w+)\"").find(fieldBody)
        val type = typeMatch?.groupValues?.get(1) ?: "string"
        fields.add("\"$fieldName\": <$type>")
    }

    val example = if (fields.isEmpty()) "" else fields.joinToString(", ")
    return "- ${tool.name}: ${tool.description}. INPUT = {$example}"
}

private fun buildSystemPrompt(mcpTools: List<McpTool>): String {
    if (mcpTools.isEmpty()) {
        return BASE_SYSTEM_PROMPT
            .replace("{MCP_ACTIONS}", "")
            .replace("{MCP_TOOL_DESCRIPTIONS}", "")
    }
    val actionNames = mcpTools.joinToString(", ") { it.name }
    val descriptions = mcpTools.joinToString("\n") { buildToolDescription(it) }
    return BASE_SYSTEM_PROMPT
        .replace("{MCP_ACTIONS}", ", $actionNames")
        .replace("{MCP_TOOL_DESCRIPTIONS}", "$descriptions\n")
}

class AgentRunner(
    private val api: LLMApiClient,
    private val memory: AgentMemory,
    private vararg val mcpProviders: McpProviderFacade
) {
    // Persists across run() calls so the agent remembers context when user confirms actions
    private val conversationHistory = mutableListOf<InputMessage>()
    // Maps tool name → provider that owns it, built fresh on each run()
    private val toolToProvider = mutableMapOf<String, McpProviderFacade>()

    fun resetHistory() {
        conversationHistory.clear()
    }

    suspend fun run(
        userTask: String,
        onStep: suspend (AgentStep) -> Unit
    ) {
        toolToProvider.clear()
        val mcpTools = mutableListOf<McpTool>()
        for (provider in mcpProviders) {
            if (!provider.isEnabled) continue
            val status = provider.connect()
            if (status is McpConnectionStatus.Connected) {
                for (tool in status.tools) {
                    toolToProvider[tool.name] = provider
                }
                mcpTools += status.tools
            }
        }

        val systemPrompt = buildSystemPrompt(mcpTools)
        val memoryContext = memory.toContextString()
        val systemWithMemory = if (memoryContext.isNotEmpty()) {
            "$systemPrompt\n\n$memoryContext"
        } else {
            systemPrompt
        }

        conversationHistory.add(InputMessage(role = "user", content = userTask))

        for (iteration in 0 until MAX_ITERATIONS) {
            val request = ChatRequest(
                model = MODEL,
                instructions = systemWithMemory,
                input = conversationHistory.toList()
            )

            val response = try {
                api.sendMessage(request)
            } catch (e: Exception) {
                onStep(AgentStep(AgentStepType.OBSERVATION, "API error: ${e.message}"))
                break
            }

            val rawText = response.extractText() ?: break

            conversationHistory.add(InputMessage(role = "assistant", content = rawText))

            val parsed = parseAgentResponse(rawText)

            if (parsed.thought != null) {
                onStep(AgentStep(AgentStepType.THOUGHT, parsed.thought))
            }

            if (parsed.action == null) {
                // Model responded without ReAct format — treat the whole response as final answer
                onStep(AgentStep(AgentStepType.FINAL_ANSWER, rawText))
                break
            }
            val action = parsed.action
            val input = parsed.input ?: ""

            if (action == "FINAL_ANSWER") {
                onStep(AgentStep(AgentStepType.FINAL_ANSWER, input))
                break
            }

            onStep(AgentStep(AgentStepType.ACTION, "$action: $input"))

            val observation = executeAction(action, input)
            onStep(AgentStep(AgentStepType.OBSERVATION, observation))

            conversationHistory.add(InputMessage(role = "user", content = "OBSERVATION: $observation"))
        }
    }

    private suspend fun executeAction(action: String, input: String): String {
        return when (action.trim().uppercase()) {
            "SEARCH_MEMORY" -> {
                val key = input.trim()
                memory.recall(key) ?: "No memory found for key: $key"
            }
            "STORE_MEMORY" -> {
                val eqIndex = input.indexOf('=')
                if (eqIndex < 0) {
                    "Error: STORE_MEMORY input must be key=value"
                } else {
                    val key = input.substring(0, eqIndex).trim()
                    val value = input.substring(eqIndex + 1).trim()
                    memory.store(key, value)
                    "Stored: $key = $value"
                }
            }
            "CALCULATE" -> {
                try {
                    val result = evalMath(input.trim())
                    "Result: $result"
                } catch (e: Exception) {
                    "Calculation error: ${e.message}"
                }
            }
            else -> {
                // MCP tool names are lowercase; model may output uppercase — normalize
                val actionName = action.trim().lowercase()
                val provider = toolToProvider[actionName]
                if (provider != null) {
                    // Validate JSON input
                    if (!input.trim().startsWith("{")) {
                        "Error: invalid JSON input for $actionName"
                    } else {
                        try { provider.callTool(actionName, input.trim()) }
                        catch (e: Exception) { "Error calling MCP tool $actionName: ${e.message}" }
                    }
                } else {
                    "Unknown action: $actionName"
                }
            }
        }
    }

    private fun evalMath(expr: String): Double {
        val tokens = tokenize(expr)
        val pos = intArrayOf(0)
        return parseExpr(tokens, pos)
    }

    private fun tokenize(expr: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val s = expr.replace(" ", "")
        while (i < s.length) {
            if (s[i].isDigit() || s[i] == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                result.add(s.substring(start, i))
            } else {
                result.add(s[i].toString())
                i++
            }
        }
        return result
    }

    private fun parseExpr(tokens: List<String>, pos: IntArray): Double {
        var result = parseTerm(tokens, pos)
        while (pos[0] < tokens.size && (tokens[pos[0]] == "+" || tokens[pos[0]] == "-")) {
            val op = tokens[pos[0]++]
            val right = parseTerm(tokens, pos)
            result = if (op == "+") result + right else result - right
        }
        return result
    }

    private fun parseTerm(tokens: List<String>, pos: IntArray): Double {
        var result = parseFactor(tokens, pos)
        while (pos[0] < tokens.size && (tokens[pos[0]] == "*" || tokens[pos[0]] == "/")) {
            val op = tokens[pos[0]++]
            val right = parseFactor(tokens, pos)
            result = if (op == "*") result * right else result / right
        }
        return result
    }

    private fun parseFactor(tokens: List<String>, pos: IntArray): Double {
        if (pos[0] >= tokens.size) return 0.0
        return if (tokens[pos[0]] == "(") {
            pos[0]++ // consume '('
            val result = parseExpr(tokens, pos)
            if (pos[0] < tokens.size && tokens[pos[0]] == ")") pos[0]++
            result
        } else {
            tokens[pos[0]++].toDouble()
        }
    }

    private data class ParsedResponse(
        val thought: String?,
        val action: String?,
        val input: String?
    )

    private fun parseAgentResponse(text: String): ParsedResponse {
        val lines = text.lines()
        var thought: String? = null
        var action: String? = null
        var input: String? = null

        for (line in lines) {
            when {
                line.startsWith("THOUGHT:") -> thought = line.removePrefix("THOUGHT:").trim()
                line.startsWith("ACTION:") -> {
                    val raw = line.removePrefix("ACTION:").trim()
                    val colonIdx = raw.indexOf(':')
                    if (colonIdx > 0) {
                        action = raw.substring(0, colonIdx).trim()
                        if (input == null) input = raw.substring(colonIdx + 1).trim()
                    } else {
                        action = raw
                    }
                }
                line.startsWith("INPUT:") -> input = line.removePrefix("INPUT:").trim()
            }
        }
        return ParsedResponse(thought, action, input)
    }
}
