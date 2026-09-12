package com.ownapps.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A faint 3x2 grip-dot grid marking a pinned row as reorderable. No padding or alignment baked
 * in — the caller sizes and positions it (e.g. as the drag handle beside the pin toggle).
 */
@Composable
fun ReorderGrip(
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                GripDot(color)
                GripDot(color)
            }
        }
    }
}

@Composable
private fun GripDot(color: Color) {
    Box(
        Modifier
            .size(3.dp)
            .clip(CircleShape)
            .background(color)
    )
}