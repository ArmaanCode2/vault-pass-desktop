package com.vaultpass.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.ui.theme.LocalVaultPassExtendedColors

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val resolvedColor = if (color == Color.Unspecified) {
        LocalVaultPassExtendedColors.current.glassBase
    } else {
        color
    }
    val resolvedBorder = if (borderColor == Color.Unspecified) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        borderColor
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(resolvedColor)
            .border(1.dp, resolvedBorder, shape)
    ) {
        content()
    }
}
