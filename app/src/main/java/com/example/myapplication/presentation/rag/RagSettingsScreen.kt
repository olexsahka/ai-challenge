package com.example.myapplication.presentation.rag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RerankConfig
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagSettingsScreen(
    viewModel: RagSettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        launch { viewModel.navigateBack.collect { onNavigateBack() } }
        launch { viewModel.snackbarMessage.collect { msg -> snackbarHostState.showSnackbar(msg) } }
    }

    val currentStrategy = when (val s = uiState) {
        is RagSettingsUiState.Idle -> s.currentStrategy
        is RagSettingsUiState.Error -> s.strategy
        else -> ChunkingStrategy.FIXED_SIZE
    }
    val currentRerankConfig = when (val s = uiState) {
        is RagSettingsUiState.Idle -> s.rerankConfig
        else -> RerankConfig()
    }

    var selectedStrategy by rememberSaveable { mutableStateOf(currentStrategy) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    var rerankEnabled by rememberSaveable { mutableStateOf(currentRerankConfig.rerankEnabled) }
    var queryRewriteEnabled by rememberSaveable { mutableStateOf(currentRerankConfig.queryRewriteEnabled) }
    var threshold by rememberSaveable { mutableFloatStateOf(currentRerankConfig.threshold) }
    var preFilterK by rememberSaveable { mutableIntStateOf(currentRerankConfig.preFilterK) }
    var postFilterK by rememberSaveable { mutableIntStateOf(currentRerankConfig.postFilterK) }

    val isSaving = uiState is RagSettingsUiState.Saving

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки RAG") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (uiState is RagSettingsUiState.Error) {
                LaunchedEffect((uiState as RagSettingsUiState.Error).message) {
                    snackbarHostState.showSnackbar((uiState as RagSettingsUiState.Error).message)
                }
            }

            Text("Стратегия чанкинга")
            Spacer(Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { if (!isSaving) dropdownExpanded = !dropdownExpanded }
            ) {
                OutlinedTextField(
                    value = selectedStrategy.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Стратегия") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    enabled = !isSaving
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    ChunkingStrategy.entries.forEach { strategy ->
                        DropdownMenuItem(
                            text = { Text(strategy.name) },
                            onClick = {
                                selectedStrategy = strategy
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (isSaving) {
                val progress = (uiState as RagSettingsUiState.Saving).progress
                if (progress != null && progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.current.toFloat() / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = { viewModel.onSave(selectedStrategy) },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSaving) "Реиндексация…" else "Сохранить")
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Ранжирование (Reranking)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Включить reranking", modifier = Modifier.weight(1f))
                Switch(
                    checked = rerankEnabled,
                    onCheckedChange = {
                        rerankEnabled = it
                        viewModel.onSaveRerankConfig(
                            RerankConfig(rerankEnabled = it, threshold = threshold,
                                preFilterK = preFilterK, postFilterK = postFilterK,
                                queryRewriteEnabled = queryRewriteEnabled)
                        )
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Переписать запрос (Query Rewrite)", modifier = Modifier.weight(1f))
                Switch(
                    checked = queryRewriteEnabled,
                    onCheckedChange = {
                        queryRewriteEnabled = it
                        viewModel.onSaveRerankConfig(
                            RerankConfig(rerankEnabled = rerankEnabled, threshold = threshold,
                                preFilterK = preFilterK, postFilterK = postFilterK,
                                queryRewriteEnabled = it)
                        )
                    }
                )
            }

            if (rerankEnabled) {
                Spacer(Modifier.height(12.dp))
                Text("Порог схожести: ${"%.2f".format(threshold)}")
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = {
                        viewModel.onSaveRerankConfig(
                            RerankConfig(rerankEnabled = rerankEnabled, threshold = threshold,
                                preFilterK = preFilterK, postFilterK = postFilterK,
                                queryRewriteEnabled = queryRewriteEnabled)
                        )
                    },
                    valueRange = 0f..0.5f,
                    steps = 49,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Text("Pre-filter K (кандидаты): $preFilterK")
                Slider(
                    value = preFilterK.toFloat(),
                    onValueChange = { preFilterK = it.toInt() },
                    onValueChangeFinished = {
                        viewModel.onSaveRerankConfig(
                            RerankConfig(rerankEnabled = rerankEnabled, threshold = threshold,
                                preFilterK = preFilterK, postFilterK = postFilterK,
                                queryRewriteEnabled = queryRewriteEnabled)
                        )
                    },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Text("Post-filter K (результат): $postFilterK")
                Slider(
                    value = postFilterK.toFloat(),
                    onValueChange = { postFilterK = it.toInt() },
                    onValueChangeFinished = {
                        viewModel.onSaveRerankConfig(
                            RerankConfig(rerankEnabled = rerankEnabled, threshold = threshold,
                                preFilterK = preFilterK, postFilterK = postFilterK,
                                queryRewriteEnabled = queryRewriteEnabled)
                        )
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
