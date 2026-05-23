package com.example.myapplication.presentation.rag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import com.example.myapplication.presentation.rag.components.MessageBubble
import com.example.myapplication.presentation.rag.components.QuickQuestionsRow
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagChatScreen(
    modifier: Modifier = Modifier,
    viewModel: RagChatViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        RagSettingsScreen(onNavigateBack = { showSettings = false })
        return
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("RAG Chat") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Настройки RAG",
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val status = state.indexStatus) {
                is IndexStatus.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is IndexStatus.Empty -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Индекс не создан", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { showSettings = true }) { Text("Настройки RAG") }
                    }
                }

                is IndexStatus.Indexing -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (status.total > 0)
                                "Индексация… ${status.current}/${status.total}"
                            else "Индексация…",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(12.dp))
                        if (status.total > 0) {
                            LinearProgressIndicator(
                                progress = { status.current.toFloat() / status.total.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                is IndexStatus.IndexError -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Ошибка: ${status.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.retry() }) { Text("Повторить") }
                    }
                }

                is IndexStatus.Ready -> {
                    ReadyContent(
                        state = state,
                        onTabSelected = viewModel::onTabSelected,
                        onSendMessage = viewModel::sendMessage,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: RagChatScreenState,
    onTabSelected: (ChatTab) -> Unit,
    onSendMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val messages = if (state.selectedTab == ChatTab.RAG) state.ragMessages else state.noRagMessages

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.imePadding()) {
        TabRow(selectedTabIndex = state.selectedTab.ordinal) {
            Tab(
                selected = state.selectedTab == ChatTab.RAG,
                onClick = { onTabSelected(ChatTab.RAG) },
                text = { Text("RAG") }
            )
            Tab(
                selected = state.selectedTab == ChatTab.NO_RAG,
                onClick = { onTabSelected(ChatTab.NO_RAG) },
                text = { Text("Без RAG") }
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages) { message ->
                MessageBubble(message = message)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        QuickQuestionsRow(
            questions = RagSampleQuestions.QUESTIONS,
            enabled = !state.isSending,
            onQuestionSelected = { question ->
                onSendMessage(question, state.selectedTab == ChatTab.RAG)
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите вопрос…") },
                enabled = !state.isSending,
                maxLines = 3
            )
            Spacer(Modifier.width(8.dp))
            if (state.isSending) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
            } else {
                IconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotBlank()) {
                            onSendMessage(text, state.selectedTab == ChatTab.RAG)
                            inputText = ""
                        }
                    }
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}
