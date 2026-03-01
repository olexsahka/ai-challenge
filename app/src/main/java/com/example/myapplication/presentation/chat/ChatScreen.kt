package com.example.myapplication.presentation.chat

import android.widget.Toast
import androidx.compose.foundation.background
import com.example.myapplication.domain.model.MessageMeta
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.example.myapplication.domain.model.Message
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(modifier: Modifier = Modifier, viewModel: ChatViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var inputText by remember { mutableStateOf("") }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val hasSplitScreen = uiState.profilePanes.isNotEmpty()
    val anyLoading = uiState.isUnrestrictedLoading || uiState.profilePanes.any { it.isLoading }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    if (uiState.isSettingsDialogVisible) {
        SettingsDialog(
            settings = uiState.settings,
            availableModels = uiState.availableModels,
            isLoadingModels = uiState.isLoadingModels,
            onSave = { viewModel.saveSettings(it) },
            onDismiss = { viewModel.hideSettingsDialog() }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Claude Chat") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = {
                        val allText = buildString {
                            appendLine("=== Unrestricted ===")
                            uiState.unrestrictedMessages.forEach { msg ->
                                val role = if (msg.isFromUser) "User" else "Assistant"
                                appendLine("$role: ${msg.content}")
                            }
                            uiState.profilePanes.forEach { pane ->
                                appendLine()
                                appendLine("=== ${pane.profile.name.ifBlank { "Restricted" }} ===")
                                pane.messages.forEach { msg ->
                                    val role = if (msg.isFromUser) "User" else "Assistant"
                                    appendLine("$role: ${msg.content}")
                                }
                            }
                        }
                        clipboardManager.setText(AnnotatedString(allText))
                        Toast.makeText(context, "All chats copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy all chats",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = { viewModel.showSettingsDialog() }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            if (hasSplitScreen) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // Unrestricted pane
                    PaneColumn(
                        label = "Unrestricted",
                        messages = uiState.unrestrictedMessages,
                        isLoading = uiState.isUnrestrictedLoading,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        usdToRub = uiState.usdToRub,
                        modifier = Modifier.weight(1f)
                    )

                    // Profile panes
                    uiState.profilePanes.forEach { pane ->
                        VerticalDivider(modifier = Modifier.fillMaxHeight())
                        PaneColumn(
                            label = pane.profile.name.ifBlank { "Restricted" },
                            messages = pane.messages,
                            isLoading = pane.isLoading,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            usdToRub = uiState.usdToRub,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                // Single pane — unrestricted only
                val listState = rememberLazyListState()
                LaunchedEffect(uiState.unrestrictedMessages.size) {
                    if (uiState.unrestrictedMessages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.unrestrictedMessages.size - 1)
                    }
                }
                MessageList(
                    messages = uiState.unrestrictedMessages,
                    isLoading = uiState.isUnrestrictedLoading,
                    listState = listState,
                    usdToRub = uiState.usdToRub,
                    modifier = Modifier.weight(1f)
                )
            }

            MessageInput(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank() && !anyLoading) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                isLoading = anyLoading
            )
        }
    }
}

@Composable
private fun PaneColumn(
    label: String,
    messages: List<Message>,
    isLoading: Boolean,
    containerColor: Color,
    usdToRub: Double,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor)
                .padding(vertical = 4.dp, horizontal = 4.dp)
        )
        MessageList(
            messages = messages,
            isLoading = isLoading,
            listState = listState,
            usdToRub = usdToRub,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    isLoading: Boolean,
    listState: LazyListState,
    usdToRub: Double,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(modifier = Modifier.padding(top = 8.dp)) }
        items(messages, key = { it.id }) { message ->
            MessageBubble(message = message, usdToRub = usdToRub)
        }
        if (isLoading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(
                                    topStart = 4.dp,
                                    topEnd = 16.dp,
                                    bottomStart = 16.dp,
                                    bottomEnd = 16.dp
                                )
                            )
                            .padding(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.padding(bottom = 4.dp)) }
    }
}

private fun estimateCostUsd(meta: MessageMeta): Double {
    val modelLower = meta.model.lowercase()
    val (inputPricePerM, outputPricePerM) = when {
        "gpt-4o-mini" in modelLower -> Pair(0.15, 0.60)
        "gpt-4o" in modelLower -> Pair(2.50, 10.00)
        "gpt-4-turbo" in modelLower -> Pair(10.00, 30.00)
        "gpt-4" in modelLower -> Pair(30.00, 60.00)
        "gpt-3.5" in modelLower -> Pair(0.50, 1.50)
        "o1-mini" in modelLower -> Pair(1.10, 4.40)
        "o1" in modelLower -> Pair(15.00, 60.00)
        "o3-mini" in modelLower -> Pair(1.10, 4.40)
        "o3" in modelLower -> Pair(10.00, 40.00)
        else -> Pair(2.50, 10.00)
    }
    return (meta.inputTokens * inputPricePerM + meta.outputTokens * outputPricePerM) / 1_000_000.0
}

@Composable
private fun MessageBubble(message: Message, usdToRub: Double) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val bubbleColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bubbleShape = if (message.isFromUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (message.isFromUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (!message.isFromUser && message.meta != null) {
                MessageMetaRow(meta = message.meta, usdToRub = usdToRub)
            }
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.content))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy message",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun MessageMetaRow(meta: MessageMeta, usdToRub: Double) {
    val costUsd = estimateCostUsd(meta)
    val costRub = costUsd * usdToRub
    val usdText = if (costUsd < 0.000001) "<$0.000001" else "$${"%.6f".format(costUsd)}"
    val rubText = if (costRub < 0.0001) "<₽0.0001" else "₽${"%.4f".format(costRub)}"
    val durationText = if (meta.durationMs >= 1000) {
        "${"%.1f".format(meta.durationMs / 1000.0)}s"
    } else {
        "${meta.durationMs}ms"
    }
    val totalTokens = meta.inputTokens + meta.outputTokens
    Text(
        text = "↑${meta.inputTokens} ↓${meta.outputTokens} · ${totalTokens}tok · $durationText · $usdText · $rubText",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message...") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            shape = RoundedCornerShape(24.dp)
        )
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank() && !isLoading,
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank() && !isLoading)
                    MaterialTheme.colorScheme.primary
                else
                    Color.Gray
            )
        }
    }
}
