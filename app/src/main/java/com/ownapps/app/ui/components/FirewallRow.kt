package com.ownapps.app.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * One app row for the Firewall list. Left: icon + label. Right: an internet [Switch].
 *
 * The switch is ON when the app has internet access and OFF when it's blocked — i.e. it reads as
 * the connection state, the inverse of the persisted "blocked" flag. Turning it (off/on) requires
 * [canToggle] — the privileged backend running *and* the master firewall switch enforcing,
 * because a per-app rule silently does nothing while Chain 3 is off. The firewall is a separate,
 * friction-free feature from the disable/enable blocker.
 */
@Composable
fun FirewallRow(
    icon: Drawable?,
    label: String,
    isBlocked: Boolean,
    canToggle: Boolean,
    onToggleBlocked: () -> Unit,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
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
        if (onTogglePin != null) {
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (isPinned) "Unpin" else "Pin",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Switch(
            checked = !isBlocked,
            onCheckedChange = { onToggleBlocked() },
            enabled = canToggle
        )
    }
}