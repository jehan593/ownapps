package com.ownapps.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared state filter for the All Apps and Firewall lists. "Enabled" means the app itself is
 *  enabled on All Apps, and that networking is allowed on Firewall (i.e. the rule is not blocked). */
enum class StateFilter(val label: String) {
    ALL("All"),
    ENABLED("Enabled"),
    DISABLED("Disabled")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StateFilterChips(selected: StateFilter, onSelected: (StateFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StateFilter.values().forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text(filter.label) }
            )
        }
    }
}
