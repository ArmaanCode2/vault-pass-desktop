package com.vaultpass.desktop.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown on the device that did not start the sync while the other device's user reviews the
 * changes. The review has no time limit, so this user must be able to end it.
 */
@Composable
fun AwaitingReviewDialog(peerName: String, onCancel: () -> Unit) {
    AlertDialog(
        // Clicking outside does nothing: cancelling a sync is a deliberate choice.
        onDismissRequest = {},
        title = {
            Text(text = "Synchronizing Vault", fontWeight = FontWeight.Bold, color = Color.White)
        },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF0EA5A1),
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp
                )
                Text(
                    text = "Waiting for $peerName to choose what to sync.\nNothing changes here until you approve it.",
                    color = Color(0xFFE8E8E8),
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color(0xFFFF6B6B))
            }
        },
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(12.dp)
    )
}
