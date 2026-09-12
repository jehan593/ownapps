package com.ownapps.app.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One app row for the All Apps list. Left: icon + label, right: an enable/disable [Switch].
 *
 * Tapping the row opens the app via [onOpen]; for a disabled row the caller confirms first (see
 * AppListScreen). Disabling needs [canDisable] (backend running and authorized); enabling is
 * always allowed.
 */
@Composable
fun AppRowWithBlock(
    icon: Drawable?,
    label: String,
    isDisabled: Boolean,
    canDisable: Boolean,
    onToggleEnabled: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    reorderGripModifier: Modifier? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 10.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon = icon)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        if (reorderGripModifier != null) {
            ReorderGrip(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = reorderGripModifier
            )
        }
        if (onTogglePin != null) {
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (isPinned) "Unpin" else "Pin"
                )
            }
        }
        // ON = enabled, OFF = disabled. Disabling needs the privileged backend; enabling is always
        // allowed.
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = !isDisabled,
            onCheckedChange = { onToggleEnabled() },
            enabled = if (isDisabled) true else canDisable
        )
    }
}
