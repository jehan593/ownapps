package com.ownapps.app.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One app row for the All Apps list. Left: icon + label, right: an enable/disable [Switch].
 *
 * Only the icon and the label open the app via [onOpen] — the rest of the row is inert so an
 * accidental tap between the elements can't launch anything. The caller confirms before opening
 * a disabled app. Disabling needs [canDisable]; enabling is always allowed.
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
    val noIndication = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(
            icon = icon,
            modifier = Modifier.clickable(
                interactionSource = noIndication,
                indication = null
            ) { onOpen() }
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clickable(
                interactionSource = noIndication,
                indication = null
            ) { onOpen() }
        )
        // Invisible filler: keeps the grip/pin/switch trailing while the clickable area stays
        // limited to the icon and the drawn label text, so blank space in the row can't launch.
        Spacer(modifier = Modifier.weight(1f))
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
