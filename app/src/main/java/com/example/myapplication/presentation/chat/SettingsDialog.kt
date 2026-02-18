package com.example.myapplication.presentation.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.model.Settings

@Composable
fun SettingsDialog(
    settings: Settings,
    onSave: (Settings) -> Unit,
    onDismiss: () -> Unit
) {
    var showWithoutRestrictions by remember { mutableStateOf(settings.showWithoutRestrictions) }
    var responseFormatDescription by remember { mutableStateOf(settings.responseFormatDescription) }
    var maxOutputTokensText by remember {
        mutableStateOf(settings.maxOutputTokens?.toString() ?: "")
    }
    var stopSequence by remember { mutableStateOf(settings.stopSequence) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Restrictions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = responseFormatDescription,
                    onValueChange = { responseFormatDescription = it },
                    label = { Text("Response format description") },
                    placeholder = { Text("Enter system prompt...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = maxOutputTokensText,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.all { it.isDigit() }) {
                            maxOutputTokensText = value
                        }
                    },
                    label = { Text("Max output tokens") },
                    placeholder = { Text("e.g. 1024") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = stopSequence,
                    onValueChange = { stopSequence = it },
                    label = { Text("Stop sequence") },
                    placeholder = { Text("e.g. END, STOP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Show split-screen comparison",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = showWithoutRestrictions,
                        onCheckedChange = { showWithoutRestrictions = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Settings(
                        showWithoutRestrictions = showWithoutRestrictions,
                        responseFormatDescription = responseFormatDescription,
                        maxOutputTokens = maxOutputTokensText.toIntOrNull(),
                        stopSequence = stopSequence
                    )
                )
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
