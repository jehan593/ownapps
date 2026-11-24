package com.ownapps.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Neutral placeholder used when a filter leaves one section of a list with no rows. */
@Composable
fun ListEmptyText(modifier: Modifier = Modifier) {
    Text(
        text = "No Apps",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
