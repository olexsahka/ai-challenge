package com.example.myapplication.presentation.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.agent.MemoryEntry
import com.example.myapplication.data.repository.TaskMemory
import com.example.myapplication.data.repository.UserInformation
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.data.db.entity.MemoryStrategy
import com.example.myapplication.data.db.entity.SessionEntity
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.RadioButton
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.MessageMeta
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AVAILABLE_MODELS = listOf(
    "gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo", "o1-mini", "o3-mini"
)
private val TEMPERATURE_OPTIONS = listOf(0.0f, 0.7f, 1.0f, 1.2f)
//Расскажи максимально подробно про историю авто и бензвионвых двигаетелей

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(modifier: Modifier = Modifier, viewModel: AgentViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (uiState.showSettings) {
        val session = uiState.activeSession
        if (session != null) {
            ContextSettingsSheet(
                session = session,
                memories = uiState.memories,
                userInformation = uiState.userInformation,
                taskMemory = uiState.taskMemory,
                onSave = { prompt, model, temp, compressionEnabled, compressionN, compressionM, memoryStrategy, slidingWindowN, stickyFactsN ->
                    viewModel.saveSessionContext(prompt, model, temp, compressionEnabled, compressionN, compressionM, memoryStrategy, slidingWindowN, stickyFactsN)
                },
                onSaveUserInformation = { viewModel.saveUserInformation(it) },
                onSaveTaskMemory = { viewModel.saveTaskMemory(it) },
                onForgetMemory = { viewModel.forgetMemory(it) },
                onForgetAll = { viewModel.forgetAllMemory() },
                onDismiss = { viewModel.hideSettings() }
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val title = uiState.activeSession?.title ?: "AI Agent"
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (uiState.activeSession != null) {
                        TextButton(
                            onClick = { viewModel.sendLargeTokenTest() },
                            enabled = !uiState.isLoading
                        ) {
                            Text(
                                "150K",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        IconButton(onClick = { viewModel.showSettings() }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Context settings",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.newSession() }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New session",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            val isBranching = uiState.activeSession?.memoryStrategy == MemoryStrategy.BRANCHING.name
            if (isBranching) {
                BranchSidebar(
                    sessions = uiState.sessions,
                    activeSessionId = uiState.activeSession?.id,
                    onSelectSession = { viewModel.selectSession(it) },
                    nodes = uiState.branchNodes,
                    activeNodeId = uiState.activeNodeId,
                    onSelectNode = { viewModel.selectBranchNode(it) },
                    onForkNode = { viewModel.forkCurrentNode() },
                    onRenameNode = { id, label -> viewModel.renameNode(id, label) },
                    modifier = Modifier
                        .width(140.dp)
                        .fillMaxHeight()
                )
            } else {
                SessionSidebar(
                    sessions = uiState.sessions,
                    activeSessionId = uiState.activeSession?.id,
                    onSelect = { viewModel.selectSession(it) },
                    modifier = Modifier
                        .width(110.dp)
                        .fillMaxHeight()
                )
            }

            VerticalDivider(modifier = Modifier.fillMaxHeight())

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (uiState.activeSession == null) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Tap + to start a new session",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val activeStrategy = uiState.activeSession?.memoryStrategy
                    val summary = uiState.activeSummary
                    if (summary != null && activeStrategy == MemoryStrategy.COMPRESSION.name) {
                        SummaryPinBanner(summary = summary.summary)
                    }
                    if (uiState.activeFacts.isNotEmpty() && activeStrategy == MemoryStrategy.STICKY_FACTS.name) {
                        FactsPinBanner(facts = uiState.activeFacts)
                    }
                    MessageList(
                        messages = messages,
                        isLoading = uiState.isLoading,
                        modifier = Modifier.weight(1f)
                    )
                    val totalInput = messages.sumOf { it.meta?.inputTokens ?: 0 }
                    val totalOutput = messages.sumOf { it.meta?.outputTokens ?: 0 }
                    if (totalInput > 0 || totalOutput > 0) {
                        TokenTotalsBar(totalInput = totalInput, totalOutput = totalOutput)
                    }
                    MessageInput(
                        text = inputText,
                        onTextChange = { inputText = it },
                        onSend = {
                            if (inputText.isNotBlank() && !uiState.isLoading) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        isLoading = uiState.isLoading
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSettingsSheet(
    session: SessionEntity,
    memories: List<MemoryEntry>,
    userInformation: UserInformation,
    taskMemory: TaskMemory,
    onSave: (systemPrompt: String, model: String, temperature: Float, compressionEnabled: Boolean, compressionN: Int, compressionM: Int, memoryStrategy: String, slidingWindowN: Int, stickyFactsN: Int) -> Unit,
    onSaveUserInformation: (UserInformation) -> Unit,
    onSaveTaskMemory: (TaskMemory) -> Unit,
    onForgetMemory: (String) -> Unit,
    onForgetAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var systemPrompt by rememberSaveable { mutableStateOf(session.systemPrompt) }
    var model by rememberSaveable { mutableStateOf(session.model) }
    var temperature by rememberSaveable { mutableStateOf(session.temperature) }
    var modelExpanded by remember { mutableStateOf(false) }
    var tempExpanded by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var compressionNText by rememberSaveable { mutableStateOf(session.compressionN.toString()) }
    var compressionMText by rememberSaveable { mutableStateOf(session.compressionM.toString()) }
    var selectedStrategy by rememberSaveable { mutableStateOf(session.memoryStrategy) }
    var slidingWindowNText by rememberSaveable { mutableStateOf(session.slidingWindowN.toString()) }
    var stickyFactsNText by rememberSaveable { mutableStateOf(session.stickyFactsN.toString()) }
    var userNameText by rememberSaveable { mutableStateOf(userInformation.name) }
    var userOccupationText by rememberSaveable { mutableStateOf(userInformation.occupation) }
    var userLanguageText by rememberSaveable { mutableStateOf(userInformation.language) }
    var responseStyleText by rememberSaveable { mutableStateOf(userInformation.responseStyle) }
    var responseFormatText by rememberSaveable { mutableStateOf(userInformation.responseFormat) }
    var constraintsText by rememberSaveable { mutableStateOf(userInformation.constraints) }
    var additionalNotesText by rememberSaveable { mutableStateOf(userInformation.additionalNotes) }
    var taskNameText by rememberSaveable { mutableStateOf(taskMemory.name) }
    var taskDescriptionText by rememberSaveable { mutableStateOf(taskMemory.description) }
    var taskEnabledState by rememberSaveable { mutableStateOf(taskMemory.enabled) }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Context Settings", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            }
            item {
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        AVAILABLE_MODELS.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = { model = m; modelExpanded = false }
                            )
                        }
                    }
                }
            }
            item {
                ExposedDropdownMenuBox(
                    expanded = tempExpanded,
                    onExpandedChange = { tempExpanded = it }
                ) {
                    OutlinedTextField(
                        value = temperature.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Temperature") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(tempExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = tempExpanded,
                        onDismissRequest = { tempExpanded = false }
                    ) {
                        TEMPERATURE_OPTIONS.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.toString()) },
                                onClick = { temperature = t; tempExpanded = false }
                            )
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Memory Strategy", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                val strategyLabels = listOf(
                    MemoryStrategy.FULL.name to "Full History",
                    MemoryStrategy.SLIDING_WINDOW.name to "Sliding Window",
                    MemoryStrategy.COMPRESSION.name to "Memory Compression",
                    MemoryStrategy.STICKY_FACTS.name to "Sticky Facts / Key-Value",
                    MemoryStrategy.BRANCHING.name to "Branching"
                )
                strategyLabels.forEach { (name, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selectedStrategy = name }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedStrategy == name,
                            onClick = { selectedStrategy = name }
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (selectedStrategy == MemoryStrategy.SLIDING_WINDOW.name) {
                item {
                    OutlinedTextField(
                        value = slidingWindowNText,
                        onValueChange = { slidingWindowNText = it.filter { c -> c.isDigit() } },
                        label = { Text("Send last n messages") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
            if (selectedStrategy == MemoryStrategy.STICKY_FACTS.name) {
                item {
                    OutlinedTextField(
                        value = stickyFactsNText,
                        onValueChange = { stickyFactsNText = it.filter { c -> c.isDigit() } },
                        label = { Text("Send last n messages") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
            if (selectedStrategy == MemoryStrategy.COMPRESSION.name) {
                item {
                    OutlinedTextField(
                        value = compressionNText,
                        onValueChange = { compressionNText = it.filter { c -> c.isDigit() } },
                        label = { Text("Send last n messages") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                item {
                    OutlinedTextField(
                        value = compressionMText,
                        onValueChange = { compressionMText = it.filter { c -> c.isDigit() } },
                        label = { Text("Update summary every m messages") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("User Information", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userNameText,
                    onValueChange = { userNameText = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userOccupationText,
                    onValueChange = { userOccupationText = it },
                    label = { Text("Occupation / Role") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userLanguageText,
                    onValueChange = { userLanguageText = it },
                    label = { Text("Preferred language") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = responseStyleText,
                    onValueChange = { responseStyleText = it },
                    label = { Text("Response style (e.g. concise, detailed, formal)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = responseFormatText,
                    onValueChange = { responseFormatText = it },
                    label = { Text("Response format (e.g. plain text, markdown, bullets)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = constraintsText,
                    onValueChange = { constraintsText = it },
                    label = { Text("Constraints (e.g. no code, no jargon)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = additionalNotesText,
                    onValueChange = { additionalNotesText = it },
                    label = { Text("Additional notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Task Memory", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Task Memory Usage", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = taskEnabledState,
                        onCheckedChange = { taskEnabledState = it }
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = taskNameText,
                    onValueChange = { taskNameText = it },
                    label = { Text("Task name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = taskDescriptionText,
                    onValueChange = { taskDescriptionText = it },
                    label = { Text("Task description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            }
            item {
                Button(
                    onClick = {
                        val n = compressionNText.toIntOrNull()?.coerceAtLeast(1) ?: 5
                        val m = compressionMText.toIntOrNull()?.coerceAtLeast(1) ?: 6
                        val swN = slidingWindowNText.toIntOrNull()?.coerceAtLeast(1) ?: 5
                        val compressionActive = selectedStrategy == MemoryStrategy.COMPRESSION.name
                        val sfN = stickyFactsNText.toIntOrNull()?.coerceAtLeast(1) ?: 5
                        onSave(systemPrompt, model, temperature, compressionActive, n, m, selectedStrategy, swN, sfN)
                        onSaveUserInformation(UserInformation(userNameText, userOccupationText, userLanguageText, responseStyleText, responseFormatText, constraintsText, additionalNotesText))
                        onSaveTaskMemory(TaskMemory(taskNameText, taskDescriptionText, taskEnabledState))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save") }
            }

            if (memories.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Memories", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { confirmClearAll = true }) {
                            Text("Clear all", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                items(memories, key = { it.key }) { entry ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.key, style = MaterialTheme.typography.labelMedium)
                            Text(
                                entry.value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onForgetMemory(entry.key) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Forget", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all memories?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onForgetAll(); confirmClearAll = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SessionSidebar(
    sessions: List<SessionEntity>,
    activeSessionId: String?,
    onSelect: (SessionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val fmt = remember { SimpleDateFormat("dd MMM\nHH:mm", Locale.getDefault()) }
    Column(modifier = modifier) {
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(6.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                val isActive = session.id == activeSessionId
                Text(
                    text = fmt.format(Date(session.startedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(session) }
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun BranchSidebar(
    sessions: List<SessionEntity>,
    activeSessionId: String?,
    onSelectSession: (SessionEntity) -> Unit,
    nodes: List<BranchNodeEntity>,
    activeNodeId: String?,
    onSelectNode: (BranchNodeEntity) -> Unit,
    onForkNode: () -> Unit,
    onRenameNode: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fmt = remember { SimpleDateFormat("dd MMM\nHH:mm", Locale.getDefault()) }
    var renamingNodeId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Build depth map for indentation
    val nodeMap = remember(nodes) { nodes.associateBy { it.id } }
    fun depth(nodeId: String): Int {
        var d = 0; var cur: String? = nodeMap[nodeId]?.parentId
        while (cur != null) { d++; cur = nodeMap[cur]?.parentId }
        return d
    }

    Column(modifier = modifier) {
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(6.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                val isActive = session.id == activeSessionId
                Text(
                    text = fmt.format(Date(session.startedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectSession(session) }
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .padding(6.dp)
                )
            }

            if (nodes.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        text = "Branches",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(6.dp)
                    )
                }
                items(nodes, key = { it.id }) { node ->
                    val isActiveNode = node.id == activeNodeId
                    val d = depth(node.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isActiveNode) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable { onSelectNode(node) }
                            .padding(start = (6 + d * 10).dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tree connector line indicator
                        if (d > 0) {
                            Text(
                                text = "└ ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        Text(
                            text = node.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActiveNode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { renameText = node.label; renamingNodeId = node.id },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Rename",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = onForkNode,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Fork", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (renamingNodeId != null) {
        AlertDialog(
            onDismissRequest = { renamingNodeId = null },
            title = { Text("Rename node") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Label") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renamingNodeId?.let { onRenameNode(it, renameText) }
                    renamingNodeId = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingNodeId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
            val nextMeta = if (message.isFromUser)
                messages.getOrNull(index + 1)?.meta
            else null
            MessageBubble(message = message, followingMeta = nextMeta)
        }
        if (isLoading) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Box(
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                            )
                            .padding(12.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun MessageBubble(message: Message, followingMeta: MessageMeta? = null) {
    val isUser = message.isFromUser
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isUser)
        RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    else
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(
                text = if (isUser) "You" else "Agent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
            Surface(shape = shape, color = bubbleColor, modifier = Modifier.widthIn(max = 260.dp)) {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (isUser) {
                followingMeta?.let { meta ->
                    Text(
                        text = "↑${meta.inputTokens}tok",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            } else {
                message.meta?.let { meta ->
                    val dur = if (meta.durationMs >= 1000) "${"%.1f".format(meta.durationMs / 1000.0)}s"
                    else "${meta.durationMs}ms"
                    Text(
                        text = "↓${meta.outputTokens}tok · $dur · ${meta.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TokenTotalsBar(totalInput: Int, totalOutput: Int) {
    val total = totalInput + totalOutput
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = "Dialogue: ↑$totalInput ↓$totalOutput · ${total}tok",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
        )
    }
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
            placeholder = { Text("Message...") },
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
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank() && !isLoading) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }
    }
}

@Composable
private fun SummaryPinBanner(summary: String) {
    var expanded by remember { mutableStateOf(false) }
    val firstSentence = remember(summary) {
        summary.split(Regex("(?<=[.!?])\\s+")).firstOrNull()?.trim() ?: summary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Summary",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = firstSentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Expand summary",
                tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("Conversation Summary") },
            text = {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun FactsPinBanner(facts: List<FactEntity>) {
    var expanded by remember { mutableStateOf(false) }
    val preview = remember(facts) {
        facts.firstOrNull()?.let { "${it.factKey}: ${it.factValue}" } ?: ""
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Facts (${facts.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Expand facts",
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("Stored Facts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    facts.forEach { fact ->
                        Column {
                            Text(
                                text = fact.factKey,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = fact.factValue,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("Close") }
            }
        )
    }
}
