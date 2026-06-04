package com.bnn.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bnn.app.BnnViewModel
import com.bnn.app.ui.theme.NeonGreen
import com.bnn.app.ui.theme.RelayTeal
import com.bnn.app.ui.theme.WarnOrange

/**
 * BnnFloatingHeader — compact top bar that floats above content (zIndex=1).
 * Layout:
 *   [● STATUS_DOT] [B#NN]  .  [Relay chip]  [N👥 COUNTER]  [BLE button]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BnnFloatingHeader(
    headerHeight: Dp,
    viewModel: BnnViewModel,
    colorScheme: ColorScheme,
    onPeerCounterClick: () -> Unit
) {
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val bleStatus by viewModel.bleStatus.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val relayPeers by viewModel.relayPeers.collectAsStateWithLifecycle()
    val relayEnabled by viewModel.relayEnabled.collectAsStateWithLifecycle()
    val bleRunning by viewModel.bleRunning.collectAsStateWithLifecycle()

    val totalPeerCount = connectedPeers.size + relayPeers.size

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars),
        color = colorScheme.background,
        tonalElevation = 0.dp
    ) {
        TopAppBar(
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ── Left: Status dot + Brand ─────────────────────
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Live status dot
                        StatusDot(isConnected = isConnected, isRunning = bleRunning)

                        // Brand name
                        Text(
                            text = "B#NN",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = NeonGreen
                        )

                        // Status sub-label
                        Text(
                            text = if (isConnected) deviceName else bleStatus,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }

                    // ── Right: Relay chip + Peer counter + BLE button ─
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Relay toggle chip
                        RelayChip(
                            relayEnabled = relayEnabled,
                            relayCount = relayPeers.size,
                            onToggle = {
                                if (bleRunning) viewModel.setRelayMode(!relayEnabled)
                            }
                        )

                        // Peer counter (clickable → opens peer sheet)
                        PeerCounter(
                            peerCount = totalPeerCount,
                            isConnected = isConnected,
                            onClick = onPeerCounterClick
                        )

                        // BLE Start/Stop button
                        BleToggleButton(
                            running = bleRunning,
                            onToggle = {
                                if (bleRunning) viewModel.stopBle() else viewModel.startBle()
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.height(headerHeight)
        )
    }
}

// ── Status dot (small coloured circle) ──────────────────────────────────────

@Composable
private fun StatusDot(isConnected: Boolean, isRunning: Boolean) {
    val dotColor = when {
        isConnected -> NeonGreen
        isRunning   -> WarnOrange
        else        -> Color.Gray
    }
    Canvas(modifier = Modifier.size(8.dp)) {
        drawCircle(color = dotColor, radius = size.minDimension / 2, center = Offset(size.width / 2, size.height / 2))
    }
}

// ── Relay toggle chip ─────────────────────────────────────────────────────────

@Composable
private fun RelayChip(
    relayEnabled: Boolean,
    relayCount: Int,
    onToggle: () -> Unit
) {
    val chipColor = if (relayEnabled) RelayTeal else Color.Gray
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = if (relayEnabled) RelayTeal.copy(alpha = 0.15f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, chipColor.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.WifiTethering,
                contentDescription = "Relay",
                tint = chipColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = if (relayEnabled && relayCount > 0) "Relay $relayCount" else "Relay",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = chipColor,
                fontSize = 10.sp
            )
        }
    }
}

// ── Peer counter ──────────────────────────────────────────────────────────────

@Composable
private fun PeerCounter(
    peerCount: Int,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val countColor = when {
        isConnected && peerCount > 0 -> Color(0xFF007AFF) // iOS blue
        else -> Color.Gray
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Group,
            contentDescription = "Connected peers",
            tint = countColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "$peerCount",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = countColor,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

// ── BLE toggle button ─────────────────────────────────────────────────────────

@Composable
private fun BleToggleButton(running: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(8.dp),
        color = if (running) Color(0xFF1A0000) else NeonGreen.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (running) Color.Red.copy(alpha = 0.7f) else NeonGreen.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (running) Icons.Filled.Stop else Icons.Filled.Bluetooth,
                contentDescription = if (running) "Stop BLE" else "Start BLE",
                tint = if (running) Color.Red else NeonGreen,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = if (running) "Stop" else "Start",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (running) Color.Red else NeonGreen,
                fontSize = 10.sp
            )
        }
    }
}
