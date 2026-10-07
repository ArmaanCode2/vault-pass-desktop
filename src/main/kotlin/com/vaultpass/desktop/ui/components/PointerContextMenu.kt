package com.vaultpass.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PointerContextMenu(
    items: @Composable (closeMenu: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var cursorPosition by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press) {
                        if (event.button?.let { it == PointerButton.Secondary } == true) {
                            val position = event.changes.first().position
                            cursorPosition = with(density) { 
                                DpOffset(position.x.toDp() + 4.dp, position.y.toDp() + 4.dp)
                            }
                            expanded = true
                            event.changes.first().consume()
                        }
                    }
                }
            }
        }
    ) {
        content()
        Box(modifier = Modifier.padding(start = cursorPosition.x, top = cursorPosition.y).size(0.dp)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset.Zero,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            ) {
                items { expanded = false }
            }
        }
    }
}
