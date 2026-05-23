package com.example.myapplication.presentation.rag.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun QuickQuestionsRow(
    questions: List<String>,
    enabled: Boolean,
    onQuestionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        questions.forEachIndexed { index, question ->
            if (index > 0) Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = false,
                onClick = { if (enabled) onQuestionSelected(question) },
                label = { Text(question, maxLines = 1) },
                enabled = enabled
            )
        }
    }
}
