package com.vaultpass.desktop.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.zIndex
import com.vaultpass.desktop.ui.components.LocalLayoutCoordinator
import com.vaultpass.desktop.ui.theme.LocalAnimationsEnabled

enum class FixedPane { Left, Right }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ResizableSplitPane(
    modifier: Modifier = Modifier,
    initialWidth: Float,
    minWidth: Float,
    maxWidth: Float,
    flexiblePaneMinWidth: Float = 0f,
    fixedPane: FixedPane = FixedPane.Left,
    showFixedPane: Boolean = true,
    onWidthChangeFinished: (Float) -> Unit,
    leftPane: @Composable (Modifier) -> Unit,
    rightPane: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerWidth = this.maxWidth.value
        
        val actualMaxWidth = maxOf(minWidth, minOf(maxWidth, containerWidth - flexiblePaneMinWidth))
        val clampedInitialWidth = initialWidth.coerceIn(minWidth, actualMaxWidth)
        
        var localWidth by remember { mutableStateOf(clampedInitialWidth) }
        val coordinator = LocalLayoutCoordinator.current

        var isHovered by remember { mutableStateOf(false) }
        var isDragging by remember { mutableStateOf(false) }

        LaunchedEffect(initialWidth) {
            if (!isDragging) {
                localWidth = initialWidth.coerceIn(minWidth, actualMaxWidth)
            }
        }
        
        LaunchedEffect(containerWidth, minWidth, maxWidth, flexiblePaneMinWidth) {
            val dynamicMaxWidth = minOf(maxWidth, containerWidth - flexiblePaneMinWidth)
            val clamped = localWidth.coerceIn(minWidth, maxOf(minWidth, dynamicMaxWidth))
            if (clamped != localWidth) {
                localWidth = clamped
                onWidthChangeFinished(localWidth)
            }
        }

        val animationsEnabled = LocalAnimationsEnabled.current
        
        val dividerWidth by animateDpAsState(
            targetValue = if (isDragging) 3.dp else if (isHovered) 2.dp else 1.dp,
            animationSpec = if (animationsEnabled) tween(durationMillis = 150) else snap()
        )

        val dividerColor = if (isDragging || isHovered) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDragging) 0.8f else 0.5f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }

        // Animate the fixed pane width smoothly when appearing/disappearing
        val targetFixedWidth = if (showFixedPane) localWidth.dp else 0.dp
        val animatedFixedWidth by animateDpAsState(
            targetValue = targetFixedWidth,
            animationSpec = if (!animationsEnabled || isDragging) snap() else tween(durationMillis = 300)
        )

        // Completely remove divider and pane if width is practically 0
        val isFixedPaneVisible = animatedFixedWidth > 1.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (fixedPane == FixedPane.Left) {
                if (isFixedPaneVisible) {
                    Box(modifier = Modifier.width(animatedFixedWidth).fillMaxHeight()) {
                        leftPane(Modifier.fillMaxSize())
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    leftPane(Modifier.fillMaxSize())
                }
            }

            if (isFixedPaneVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(12.dp)
                        .zIndex(10f)
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR)))
                        .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { isDragging = true },
                                onDragEnd = {
                                    isDragging = false
                                    onWidthChangeFinished(localWidth)
                                },
                                onDragCancel = {
                                    isDragging = false
                                    onWidthChangeFinished(localWidth)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                val effectiveDrag = if (fixedPane == FixedPane.Left) dragAmount.toDp().value else -dragAmount.toDp().value
                                val attemptedNewWidth = localWidth + effectiveDrag
                                
                                val clampedMin = maxOf(minWidth, attemptedNewWidth)
                                val dynamicMaxWidth = minOf(maxWidth, containerWidth - flexiblePaneMinWidth)
                                var clampedMax = minOf(clampedMin, maxOf(minWidth, dynamicMaxWidth))
                                
                                val globalFreeSpace = coordinator.availableWidthForResizing
                                // Global layout constraint
                                if (clampedMax > localWidth) {
                                    val delta = clampedMax - localWidth
                                    if (delta > globalFreeSpace && globalFreeSpace >= 0) {
                                        clampedMax = localWidth + globalFreeSpace
                                    } else if (globalFreeSpace < 0) {
                                        clampedMax = localWidth // No expansion allowed
                                    }
                                }
                                localWidth = clampedMax
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(dividerWidth)
                            .background(dividerColor)
                    )
                }
            }

            if (fixedPane == FixedPane.Right) {
                if (isFixedPaneVisible) {
                    Box(modifier = Modifier.width(animatedFixedWidth).fillMaxHeight()) {
                        rightPane(Modifier.fillMaxSize())
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    rightPane(Modifier.fillMaxSize())
                }
            }
        }
    }
}
