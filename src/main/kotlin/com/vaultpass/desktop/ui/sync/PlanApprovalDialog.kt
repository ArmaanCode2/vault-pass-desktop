package com.vaultpass.desktop.ui.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vaultpass.desktop.ui.viewmodels.PlanApprovalRequest

/**
 * Shown on the device that did not start the sync: what the other device's review will change
 * here. Nothing changes on either device unless the user applies it.
 */
@Composable
fun PlanApprovalDialog(
    request: PlanApprovalRequest,
    onApply: () -> Unit,
    onDecline: () -> Unit
) {
    val summary = request.summary
    Dialog(
        // Closing the dialog any other way is a decline, so the other device isn't left waiting.
        onDismissRequest = onDecline,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            border = BorderStroke(1.dp, Color(0xFF2C2C2C))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = Color(0xFF0EA5A1),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Apply changes from ${request.peerName}?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${request.peerName} wants to change this device: ${summary.added} added, " +
                        "${summary.updated} updated, ${summary.deleted} deleted.",
                    fontSize = 13.sp,
                    color = Color(0xFFCCCCCC),
                    textAlign = TextAlign.Center
                )
                if (summary.deleted > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Deleted entries go to the recycle bin.",
                        fontSize = 12.sp,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B)),
                        border = BorderStroke(1.dp, Color(0xFF3A2020))
                    ) {
                        Text("Decline")
                    }
                    Button(
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
                    ) {
                        Text("Apply", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
