package com.example.myapplication.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.data.repository.SettingsRepository
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.RestrictionProfile
import com.example.myapplication.domain.model.Settings
import com.example.myapplication.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val settingsRepository: SettingsRepository,
    private val api: LLMApiClient,
    private val httpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        val settings = settingsRepository.getSettings()
        _uiState.update {
            it.copy(
                settings = settings,
                profilePanes = settings.profiles.map { profile -> PaneState(profile = profile) }
            )
        }
        fetchUsdToRub()
    }

    private fun fetchUsdToRub() {
        viewModelScope.launch {
            try {
                val rate = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("https://www.cbr-xml-daily.ru/daily_json.js")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: return@withContext null
                        val json = JSONObject(body)
                        json.getJSONObject("Valute")
                            .getJSONObject("USD")
                            .getDouble("Value")
                    }
                }
                if (rate != null) {
                    _uiState.update { it.copy(usdToRub = rate) }
                }
            } catch (_: Exception) {
                // keep default 90.0
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return

        val trimmed = content.trim()
        val settings = _uiState.value.settings
        val unrestrictedGenPrompt = settings.unrestrictedGeneratePromptFirst

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            content = trimmed,
            isFromUser = true
        )

        // Add user message to unrestricted pane
        _uiState.update {
            it.copy(
                unrestrictedMessages = it.unrestrictedMessages + userMessage,
                isUnrestrictedLoading = true,
                error = null
            )
        }

        // Add user message to each profile pane
        _uiState.update {
            it.copy(
                profilePanes = it.profilePanes.map { pane ->
                    val paneUserMessage = userMessage.copy(id = UUID.randomUUID().toString())
                    pane.copy(messages = pane.messages + paneUserMessage, isLoading = true)
                }
            )
        }

        viewModelScope.launch {
            // Handle unrestricted pane
            launch {
                if (unrestrictedGenPrompt) {
                    sendWithPromptGeneration(
                        isUnrestricted = true,
                        paneIndex = -1,
                        instructions = null,
                        maxOutputTokens = null,
                        temperature = settings.unrestrictedTemperature,
                        model = settings.unrestrictedModel
                    )
                } else {
                    sendDirect(
                        isUnrestricted = true,
                        paneIndex = -1,
                        instructions = null,
                        maxOutputTokens = null,
                        temperature = settings.unrestrictedTemperature,
                        model = settings.unrestrictedModel
                    )
                }
            }

            // Handle each profile pane
            _uiState.value.profilePanes.forEachIndexed { index, pane ->
                val profile = pane.profile
                val instructions = buildInstructions(profile)
                launch {
                    if (profile.generatePromptFirst) {
                        sendWithPromptGeneration(
                            isUnrestricted = false,
                            paneIndex = index,
                            instructions = instructions,
                            maxOutputTokens = profile.maxOutputTokens,
                            temperature = profile.temperature,
                            model = profile.model
                        )
                    } else {
                        sendDirect(
                            isUnrestricted = false,
                            paneIndex = index,
                            instructions = instructions,
                            maxOutputTokens = profile.maxOutputTokens,
                            temperature = profile.temperature,
                            model = profile.model
                        )
                    }
                }
            }
        }
    }

    private suspend fun sendDirect(
        isUnrestricted: Boolean,
        paneIndex: Int,
        instructions: String?,
        maxOutputTokens: Int?,
        temperature: Float,
        model: String = "gpt-4o"
    ) {
        val messages = if (isUnrestricted) {
            _uiState.value.unrestrictedMessages
        } else {
            _uiState.value.profilePanes.getOrNull(paneIndex)?.messages ?: return
        }

        val result = sendMessageUseCase(messages, instructions, maxOutputTokens, temperature, model)

        result.fold(
            onSuccess = { (responseText, meta) ->
                val assistantMessage = Message(
                    id = UUID.randomUUID().toString(),
                    content = responseText,
                    isFromUser = false,
                    meta = meta
                )
                if (isUnrestricted) {
                    _uiState.update {
                        it.copy(
                            unrestrictedMessages = it.unrestrictedMessages + assistantMessage,
                            isUnrestrictedLoading = false
                        )
                    }
                } else {
                    _uiState.update {
                        val panes = it.profilePanes.toMutableList()
                        if (paneIndex < panes.size) {
                            panes[paneIndex] = panes[paneIndex].copy(
                                messages = panes[paneIndex].messages + assistantMessage,
                                isLoading = false
                            )
                        }
                        it.copy(profilePanes = panes)
                    }
                }
            },
            onFailure = { error ->
                if (isUnrestricted) {
                    _uiState.update {
                        it.copy(
                            isUnrestrictedLoading = false,
                            error = error.message ?: "Unknown error"
                        )
                    }
                } else {
                    _uiState.update {
                        val panes = it.profilePanes.toMutableList()
                        if (paneIndex < panes.size) {
                            panes[paneIndex] = panes[paneIndex].copy(isLoading = false)
                        }
                        it.copy(
                            profilePanes = panes,
                            error = error.message ?: "Unknown error"
                        )
                    }
                }
            }
        )
    }

    private suspend fun sendWithPromptGeneration(
        isUnrestricted: Boolean,
        paneIndex: Int,
        instructions: String?,
        maxOutputTokens: Int?,
        temperature: Float,
        model: String = "gpt-4o"
    ) {
        // Get current messages for this pane
        val currentMessages = if (isUnrestricted) {
            _uiState.value.unrestrictedMessages
        } else {
            _uiState.value.profilePanes.getOrNull(paneIndex)?.messages ?: return
        }

        // The last user message is the one we just added
        val lastUserMessage = currentMessages.lastOrNull { it.isFromUser } ?: return
        val originalQuestion = lastUserMessage.content

        // Replace the last user message with the meta-prompt
        val metaPrompt = "Create prompt for LLM model with next question: $originalQuestion"
        val metaUserMessage = lastUserMessage.copy(
            id = UUID.randomUUID().toString(),
            content = metaPrompt
        )

        // Update messages: replace last user message with meta-prompt
        val messagesWithMeta = currentMessages.dropLast(1) + metaUserMessage
        if (isUnrestricted) {
            _uiState.update {
                it.copy(unrestrictedMessages = messagesWithMeta)
            }
        } else {
            _uiState.update {
                val panes = it.profilePanes.toMutableList()
                if (paneIndex < panes.size) {
                    panes[paneIndex] = panes[paneIndex].copy(messages = messagesWithMeta)
                }
                it.copy(profilePanes = panes)
            }
        }

        // Step 1: Send meta-prompt
        val step1Result = sendMessageUseCase(messagesWithMeta, instructions, maxOutputTokens, temperature, model)

        step1Result.fold(
            onSuccess = { (generatedPrompt, _) ->
                // Add generated prompt as assistant message
                val assistantMsg = Message(
                    id = UUID.randomUUID().toString(),
                    content = generatedPrompt,
                    isFromUser = false
                )
                // Add generated prompt as new user message (to be sent)
                val generatedUserMsg = Message(
                    id = UUID.randomUUID().toString(),
                    content = generatedPrompt,
                    isFromUser = true
                )

                if (isUnrestricted) {
                    _uiState.update {
                        it.copy(
                            unrestrictedMessages = it.unrestrictedMessages + assistantMsg + generatedUserMsg
                        )
                    }
                } else {
                    _uiState.update {
                        val panes = it.profilePanes.toMutableList()
                        if (paneIndex < panes.size) {
                            panes[paneIndex] = panes[paneIndex].copy(
                                messages = panes[paneIndex].messages + assistantMsg + generatedUserMsg
                            )
                        }
                        it.copy(profilePanes = panes)
                    }
                }

                // Step 2: Send the generated prompt
                val step2Messages = if (isUnrestricted) {
                    _uiState.value.unrestrictedMessages
                } else {
                    _uiState.value.profilePanes.getOrNull(paneIndex)?.messages ?: return
                }

                val step2Result = sendMessageUseCase(step2Messages, instructions, maxOutputTokens, temperature, model)

                step2Result.fold(
                    onSuccess = { (finalResponse, finalMeta) ->
                        val finalMsg = Message(
                            id = UUID.randomUUID().toString(),
                            content = finalResponse,
                            isFromUser = false,
                            meta = finalMeta
                        )
                        if (isUnrestricted) {
                            _uiState.update {
                                it.copy(
                                    unrestrictedMessages = it.unrestrictedMessages + finalMsg,
                                    isUnrestrictedLoading = false
                                )
                            }
                        } else {
                            _uiState.update {
                                val panes = it.profilePanes.toMutableList()
                                if (paneIndex < panes.size) {
                                    panes[paneIndex] = panes[paneIndex].copy(
                                        messages = panes[paneIndex].messages + finalMsg,
                                        isLoading = false
                                    )
                                }
                                it.copy(profilePanes = panes)
                            }
                        }
                    },
                    onFailure = { error ->
                        setError(isUnrestricted, paneIndex, error.message ?: "Unknown error")
                    }
                )
            },
            onFailure = { error ->
                setError(isUnrestricted, paneIndex, error.message ?: "Unknown error")
            }
        )
    }

    private fun setError(isUnrestricted: Boolean, paneIndex: Int, message: String) {
        if (isUnrestricted) {
            _uiState.update {
                it.copy(isUnrestrictedLoading = false, error = message)
            }
        } else {
            _uiState.update {
                val panes = it.profilePanes.toMutableList()
                if (paneIndex < panes.size) {
                    panes[paneIndex] = panes[paneIndex].copy(isLoading = false)
                }
                it.copy(profilePanes = panes, error = message)
            }
        }
    }

    private fun buildInstructions(profile: RestrictionProfile): String? {
        val base = profile.responseFormatDescription.takeIf { it.isNotBlank() }
        val stop = profile.stopSequence.takeIf { it.isNotBlank() }?.let {
            "You must stop generating immediately when you would output any of these sequences: $it"
        }
        return listOfNotNull(base, stop)
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    fun showSettingsDialog() {
        _uiState.update { it.copy(isSettingsDialogVisible = true) }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true) }
            try {
                val response = api.getModels()
                val excludePattern = Regex(
                    "image|audio|tts|transcribe|realtime|moderation|embedding|sora|whisper|dalle|search",
                    RegexOption.IGNORE_CASE
                )
                val ids = response.data
                    .map { it.id }
                    .filter { !excludePattern.containsMatchIn(it) }
                    .sorted()
                _uiState.update { it.copy(availableModels = ids, isLoadingModels = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingModels = false) }
            }
        }
    }

    fun hideSettingsDialog() {
        _uiState.update { it.copy(isSettingsDialogVisible = false) }
    }

    fun saveSettings(settings: Settings) {
        val effectiveSettings = if (settings.createLesson3Chats) {
            val lesson3Profiles = listOf(
                RestrictionProfile(
                    name = "Unrestricted",
                    responseFormatDescription = "",
                    generatePromptFirst = false,
                    temperature = 1.0f
                ),
                RestrictionProfile(
                    name = "Step by step",
                    responseFormatDescription = "Solve this task step by step",
                    generatePromptFirst = false,
                    temperature = 1.0f
                ),
                RestrictionProfile(
                    name = "Generate prompt first",
                    responseFormatDescription = "",
                    generatePromptFirst = true,
                    temperature = 1.0f
                ),
                RestrictionProfile(
                    name = "Multi-role",
                    responseFormatDescription = "Answer with multi-role reasoning. Provide the answer from the following perspectives:\n1. Analytic: analyze the problem systematically\n2. Engineer: provide a practical technical solution\n3. Critic: identify potential issues and limitations",
                    generatePromptFirst = false,
                    temperature = 1.0f
                )
            )
            settings.copy(profiles = lesson3Profiles, createLesson3Chats = false)
        } else {
            settings
        }

        settingsRepository.saveSettings(effectiveSettings)
        // Rebuild panes: keep messages for profiles that still exist (matched by index),
        // add empty panes for new profiles, drop panes for removed profiles.
        val oldPanes = _uiState.value.profilePanes
        val newPanes = effectiveSettings.profiles.mapIndexed { index, profile ->
            if (index < oldPanes.size) {
                oldPanes[index].copy(profile = profile)
            } else {
                PaneState(profile = profile)
            }
        }
        _uiState.update {
            it.copy(
                settings = effectiveSettings,
                isSettingsDialogVisible = false,
                profilePanes = newPanes
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
