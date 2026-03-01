package com.example.myapplication.agent

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage

private const val MAX_ITERATIONS = 6
private const val MODEL = "gpt-4o-mini"

private val SYSTEM_PROMPT = """
You are an autonomous AI agent with memory and planning capabilities.
You operate in a loop: Think → Act → Observe → Remember → Repeat until done.

You have access to these tools (respond with EXACTLY this format):
THOUGHT: <your internal reasoning>
ACTION: <one of: SEARCH_MEMORY, STORE_MEMORY, CALCULATE, FINAL_ANSWER>
INPUT: <input for the action>

Tool descriptions:
- SEARCH_MEMORY: Retrieve a stored memory by key. INPUT = key name
- STORE_MEMORY: Save a fact. INPUT = key=value
- CALCULATE: Evaluate a math expression. INPUT = expression
- FINAL_ANSWER: Provide the final answer to the user. INPUT = your answer

After each action you will receive an OBSERVATION. Continue until you use FINAL_ANSWER.
Only output one step at a time.
""".trimIndent()

class AgentRunner(
    private val api: AnthropicApi,
    private val memory: AgentMemory
) {
    suspend fun run(
        userTask: String,
        onStep: suspend (AgentStep) -> Unit
    ) {
        val memoryContext = memory.toContextString()
        val systemWithMemory = if (memoryContext.isNotEmpty()) {
            "$SYSTEM_PROMPT\n\n$memoryContext"
        } else {
            SYSTEM_PROMPT
        }

        val conversationHistory = mutableListOf<InputMessage>()
        conversationHistory.add(InputMessage(role = "user", content = userTask))

        for (iteration in 0 until MAX_ITERATIONS) {
            val request = ChatRequest(
                model = MODEL,
                instructions = systemWithMemory,
                input = conversationHistory.toList(),
                maxOutputTokens = 512
            )

            val response = try {
                api.sendMessage(request)
            } catch (e: Exception) {
                onStep(AgentStep(AgentStepType.OBSERVATION, "API error: ${e.message}"))
                break
            }

            val rawText = response.output
                .firstOrNull { it.type == "message" }
                ?.content
                ?.firstOrNull { it.type == "output_text" }
                ?.text ?: break

            conversationHistory.add(InputMessage(role = "assistant", content = rawText))

            val parsed = parseAgentResponse(rawText)

            if (parsed.thought != null) {
                onStep(AgentStep(AgentStepType.THOUGHT, parsed.thought))
            }

            val action = parsed.action ?: break
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

    private fun executeAction(action: String, input: String): String {
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
            else -> "Unknown action: $action"
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
