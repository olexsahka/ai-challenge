package com.example.myapplication.presentation.rag.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.rag.model.RagChunk

@Composable
fun SourcesPanel(
    sources: List<RagChunk>,
    scores: List<Float> = emptyList(),
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = if (expanded) "Источники (${sources.size}) ▲" else "Источники (${sources.size}) ▼",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.clickable { expanded = !expanded }
            )
            if (expanded) {
                sources.forEachIndexed { index, chunk ->
                    val label = chunk.section?.let { "${chunk.title} / $it" } ?: chunk.title
                    val score = scores.getOrNull(index)
                    val scoreStr = if (score != null) "  ·  ${"%.2f".format(score)}" else ""
                    Text(
                        text = "• $label$scoreStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
