package com.example.myapplication.presentation.rag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.rag.model.ChunkingStrategy
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

    var selectedStrategy by rememberSaveable { mutableStateOf(currentStrategy) }
    var dropdownExpanded by remember { mutableStateOf(false) }

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
        }
    }
}
