package com.vaultpass.desktop.ui.sync

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vaultpass.desktop.domain.sync.SyncState
import com.vaultpass.desktop.ui.viewmodels.SyncViewModel
import kotlinx.coroutines.launch

@Composable
fun QrPairingDialog(
    syncViewModel: SyncViewModel,
    onDismiss: () -> Unit
) {
    val qrBitmap by syncViewModel.qrCodeBitmap.collectAsState()
    val syncState by syncViewModel.syncState.collectAsState()
    val pendingPairingRequest by syncViewModel.pendingPairingRequest.collectAsState()

    var isFirewallGranted by remember { mutableStateOf<Boolean?>(null) }
    var isRequestingUac by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        isFirewallGranted = com.vaultpass.desktop.data.platform.WindowsFirewallHelper.isFirewallRuleConfigured()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .width(460.dp)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2C2C))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = Color(0xFF0EA5A1),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Pair Mobile Device",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF888888)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (syncState) {
                    SyncState.AWAITING_LOCAL_APPROVAL -> {
                        // Phone initiated connection, asking Desktop user for approval
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = Color(0xFF0EA5A1),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Incoming Pairing Connection",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Phone connected: \"${pendingPairingRequest?.deviceName ?: "Android Device"}\". Accept pairing with this device?",
                            fontSize = 13.sp,
                            color = Color(0xFFCCCCCC),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { syncViewModel.declinePairing() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A2020))
                            ) {
                                Text("Decline")
                            }
                            Button(
                                onClick = { syncViewModel.acceptPairing() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF0EA5A1),
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("Accept", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    SyncState.WAITING_FOR_REMOTE_APPROVAL -> {
                        // Desktop accepted, waiting for phone approval
                        CircularProgressIndicator(
                            color = Color(0xFF0EA5A1),
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Waiting for other device to accept pairing...",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE0E0E0),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please confirm the prompt on your mobile screen.",
                            fontSize = 12.sp,
                            color = Color(0xFF888888),
                            textAlign = TextAlign.Center
                        )
                    }

                    else -> {
                        // Default QR display state
                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap!!,
                                    contentDescription = "Pairing QR Code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .background(Color(0xFF242424), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFF0EA5A1))
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Scan this QR code with VaultPass on your Android phone to pair.",
                            fontSize = 13.sp,
                            color = Color(0xFFDDDDDD),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val allIps = syncViewModel.lanDiscoveryManager.getAllLocalIpAddresses()
                        val primaryIp = allIps.firstOrNull() ?: "127.0.0.1"
                        Text(
                            text = "Desktop IP: $primaryIp:53853",
                            fontSize = 11.sp,
                            color = Color(0xFF0EA5A1),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        if (allIps.size > 1) {
                            Text(
                                text = "Alternative IPs: ${allIps.drop(1).joinToString(", ")}",
                                fontSize = 10.sp,
                                color = Color(0xFF888888)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF252525)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "⚡ Automatic Reverse Sync is enabled. If pairing connects normally, no firewall change is needed.",
                                    fontSize = 11.sp,
                                    color = Color(0xFFB0B0B0),
                                    lineHeight = 15.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "If your Windows Firewall strictly blocks connections and pairing fails, you can optionally click below to allow network access:",
                                    fontSize = 11.sp,
                                    color = Color(0xFF888888),
                                    lineHeight = 15.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (isFirewallGranted == true) {
                                    Text("🟢 Windows Firewall Access: Allowed", fontSize = 11.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            if (!isRequestingUac) {
                                                isRequestingUac = true
                                                coroutineScope.launch {
                                                    val res = com.vaultpass.desktop.data.platform.WindowsFirewallHelper.requestFirewallPermission()
                                                    isFirewallGranted = res.getOrDefault(false)
                                                    isRequestingUac = false
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (isRequestingUac) "Waiting for Windows confirmation..." else "Allow Windows Network Access (Optional)",
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
