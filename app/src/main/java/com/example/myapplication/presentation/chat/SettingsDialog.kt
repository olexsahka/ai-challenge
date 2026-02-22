package com.example.myapplication.presentation.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.model.RestrictionProfile
import com.example.myapplication.domain.model.Settings

private val TEMPERATURE_OPTIONS = listOf(0.0f, 0.7f, 1.0f, 1.2f)

private data class MutableProfile(
    var name: String = "",
    var responseFormatDescription: String = "",
    var maxOutputTokensText: String = "",
    var stopSequence: String = "",
    var generatePromptFirst: Boolean = false,
    var temperature: Float = 1.0f,
    var model: String = "gpt-4o"
)

private fun RestrictionProfile.toMutable() = MutableProfile(
    name = name,
    responseFormatDescription = responseFormatDescription,
    maxOutputTokensText = maxOutputTokens?.toString() ?: "",
    stopSequence = stopSequence,
    generatePromptFirst = generatePromptFirst,
    temperature = temperature,
    model = model
)

private fun MutableProfile.toDomain() = RestrictionProfile(
    name = name,
    responseFormatDescription = responseFormatDescription,
    maxOutputTokens = maxOutputTokensText.toIntOrNull(),
    stopSequence = stopSequence,
    generatePromptFirst = generatePromptFirst,
    temperature = temperature,
    model = model
)

@Composable
fun SettingsDialog(
    settings: Settings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onSave: (Settings) -> Unit,
    onDismiss: () -> Unit
) {
    val profiles = remember {
        mutableStateListOf(*settings.profiles.map { it.toMutable() }.toTypedArray())
    }
    var unrestrictedGeneratePromptFirst by remember {
        mutableStateOf(settings.unrestrictedGeneratePromptFirst)
    }
    var unrestrictedTemperature by remember {
        mutableStateOf(settings.unrestrictedTemperature)
    }
    var unrestrictedModel by remember {
        mutableStateOf(settings.unrestrictedModel)
    }
    var createLesson3Chats by remember {
        mutableStateOf(settings.createLesson3Chats)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Unrestricted profile card (always shown, not deletable)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.secondary,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Unrestricted",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Generate prompt first",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Auto-generate an optimized prompt before sending",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = unrestrictedGeneratePromptFirst,
                            onCheckedChange = { unrestrictedGeneratePromptFirst = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TemperatureSelector(
                        selected = unrestrictedTemperature,
                        onSelect = { unrestrictedTemperature = it }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ModelSelector(
                        selected = unrestrictedModel,
                        models = availableModels,
                        isLoading = isLoadingModels,
                        onSelect = { unrestrictedModel = it }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Create 4 chats for 3rd lesson",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Auto-create 4 restriction profiles for lesson 3",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = createLesson3Chats,
                        onCheckedChange = { createLesson3Chats = it }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                profiles.forEachIndexed { index, profile ->
                    ProfileCard(
                        index = index,
                        profile = profile,
                        availableModels = availableModels,
                        isLoadingModels = isLoadingModels,
                        onUpdate = { profiles[index] = it },
                        onDelete = { profiles.removeAt(index) }
                    )
                    if (index < profiles.lastIndex) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                if (profiles.size < 4) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = { profiles.add(MutableProfile()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Add profile", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(Settings(
                    profiles = profiles.map { it.toDomain() },
                    unrestrictedGeneratePromptFirst = unrestrictedGeneratePromptFirst,
                    unrestrictedTemperature = unrestrictedTemperature,
                    unrestrictedModel = unrestrictedModel,
                    createLesson3Chats = createLesson3Chats
                ))
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun TemperatureSelector(
    selected: Float,
    onSelect: (Float) -> Unit
) {
    Column {
        Text(
            text = "Temperature",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TEMPERATURE_OPTIONS.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            text = if (option == option.toLong().toFloat()) {
                                option.toLong().toString()
                            } else {
                                option.toString()
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelSelector(
    selected: String,
    models: List<String>,
    isLoading: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = "Model",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 8.dp))
                Text(text = "Loading models...", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selected.ifBlank { "Select model" },
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                val displayList = if (models.isEmpty()) listOf(selected) else models
                displayList.forEach { modelId ->
                    DropdownMenuItem(
                        text = { Text(modelId) },
                        onClick = {
                            onSelect(modelId)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    index: Int,
    profile: MutableProfile,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onUpdate: (MutableProfile) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Profile ${index + 1}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete profile",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        OutlinedTextField(
            value = profile.name,
            onValueChange = { onUpdate(profile.copy(name = it)) },
            label = { Text("Name") },
            placeholder = { Text("e.g. Formal tone") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = profile.responseFormatDescription,
            onValueChange = { onUpdate(profile.copy(responseFormatDescription = it)) },
            label = { Text("System prompt") },
            placeholder = { Text("Enter restrictions...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = profile.maxOutputTokensText,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    onUpdate(profile.copy(maxOutputTokensText = value))
                }
            },
            label = { Text("Max output tokens") },
            placeholder = { Text("e.g. 1024") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = profile.stopSequence,
            onValueChange = { onUpdate(profile.copy(stopSequence = it)) },
            label = { Text("Stop sequence") },
            placeholder = { Text("e.g. END, STOP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Generate prompt first",
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = profile.generatePromptFirst,
                onCheckedChange = { onUpdate(profile.copy(generatePromptFirst = it)) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        TemperatureSelector(
            selected = profile.temperature,
            onSelect = { onUpdate(profile.copy(temperature = it)) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModelSelector(
            selected = profile.model,
            models = availableModels,
            isLoading = isLoadingModels,
            onSelect = { onUpdate(profile.copy(model = it)) }
        )
    }
}
