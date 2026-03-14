package com.example.myapplication.agent

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage
import com.example.myapplication.data.api.model.extractText
import com.example.myapplication.data.mcp.McpConnectionStatus
import com.example.myapplication.data.mcp.McpRepository
import com.example.myapplication.data.mcp.McpTool
import org.json.JSONObject

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
""".trimIndent()

private fun buildToolDescription(tool: McpTool): String {
    val props = tool.inputSchema.optJSONObject("properties") ?: return "- ${tool.name}: ${tool.description}. INPUT = {}"
    val fields = mutableListOf<String>()
    val keys = props.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val prop = props.optJSONObject(key)
        val type = prop?.optString("type", "string") ?: "string"
        fields.add("\"$key\": <$type>")
    }
    val example = fields.joinToString(", ")
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
    private val api: AnthropicApi,
    private val memory: AgentMemory,
    private val mcpRepository: McpRepository? = null
) {
    suspend fun run(
        userTask: String,
        onStep: suspend (AgentStep) -> Unit
    ) {
        val mcpTools: List<McpTool> = if (mcpRepository != null && mcpRepository.vkusVillEnabled) {
            val status = mcpRepository.connect()
            if (status is McpConnectionStatus.Connected) status.tools else emptyList()
        } else {
            emptyList()
        }

        val systemPrompt = buildSystemPrompt(mcpTools)
        val memoryContext = memory.toContextString()
        val systemWithMemory = if (memoryContext.isNotEmpty()) {
            "$systemPrompt\n\n$memoryContext"
        } else {
            systemPrompt
        }

        val conversationHistory = mutableListOf<InputMessage>()
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
                if (mcpRepository != null && mcpRepository.vkusVillEnabled && mcpRepository.isConnected) {
                    try {
                        val args = JSONObject(input.trim())
                        mcpRepository.callTool(action.trim(), args)
                    } catch (e: Exception) {
                        "Error calling MCP tool $action: ${e.message}"
                    }
                } else {
                    "Unknown action: $action"
                }
            }
        }
    }

    private fun evalMath(expr: String): Double {
        // Simple recursive descent parser for +, -, *, /
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
                line.startsWith("ACTION:") -> action = line.removePrefix("ACTION:").trim()
                line.startsWith("INPUT:") -> input = line.removePrefix("INPUT:").trim()
            }
        }
        return ParsedResponse(thought, action, input)
    }
}
