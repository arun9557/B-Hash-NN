package com.bnn.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bnn.app.BnnViewModel
import com.bnn.app.ui.theme.NeonGreen
import com.bnn.app.ui.theme.RelayTeal

/**
 * BnnPeerListSheet — "Your Network" bottom sheet.
 * Shows:
 *   - BLE PEERS section (gateway + direct BLE connections)
 *   - RELAY PEERS section (mesh relay peers when relay mode is ON)
 * Mirrors bitchat's MeshPeerListSheet design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BnnPeerListSheet(
    viewModel: BnnViewModel,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val relayPeers by viewModel.relayPeers.collectAsStateWithLifecycle()
    val relayEnabled by viewModel.relayEnabled.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    // Animated top bar alpha (fades in as user scrolls)
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    val totalCount = connectedPeers.size + relayPeers.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        dragHandle = {
            // Custom drag handle in neon green
            Surface(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = NeonGreen.copy(alpha = 0.4f)
            ) {}
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Scrollable content
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 600.dp),
                contentPadding = PaddingValues(top = 64.dp, bottom = 32.dp)
            ) {
                // ── Summary header ─────────────────────────────────────
                item(key = "summary") {
                    NetworkSummaryBanner(
                        totalPeers = totalCount,
                        isConnected = isConnected,
                        colorScheme = colorScheme
                    )
                }

                // ── BLE PEERS section ──────────────────────────────────
                item(key = "ble_header") {
                    SectionHeader(
                        text = "BLE PEERS",
                        colorScheme = colorScheme
                    )
                }

                if (connectedPeers.isEmpty()) {
                    item(key = "ble_empty") {
                        EmptyHint(
                            text = if (isConnected) "Connecting…" else "No gateway connected",
                            colorScheme = colorScheme
                        )
                    }
                } else {
                    items(
                        items = connectedPeers,
                        key = { "ble_$it" }
                    ) { peer ->
                        PeerRow(
                            name = peer,
                            icon = Icons.Filled.Bluetooth,
                            iconTint = NeonGreen,
                            isDirect = true,
                            colorScheme = colorScheme
                        )
                    }
                }

                // ── RELAY PEERS section (only when relay mode ON) ─────
                if (relayEnabled) {
                    item(key = "relay_header") {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(
                            text = "RELAY PEERS",
                            colorScheme = colorScheme
                        )
                    }

                    if (relayPeers.isEmpty()) {
                        item(key = "relay_empty") {
                            EmptyHint(
                                text = "Scanning for mesh peers…",
                                colorScheme = colorScheme
                            )
                        }
                    } else {
                        items(
                            items = relayPeers,
                            key = { "relay_$it" }
                        ) { peer ->
                            PeerRow(
                                name = peer,
                                icon = Icons.Filled.WifiTethering,
                                iconTint = RelayTeal,
                                isDirect = false,
                                colorScheme = colorScheme
                            )
                        }
                    }
                }
            }

            // ── Animated top bar ───────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = colorScheme.surface.copy(alpha = topBarAlpha)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Your Network",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = NeonGreen
                    )
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Done",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = NeonGreen
                        )
                    }
                }
            }
        }
    }
}

// ── Network summary banner ───────────────────────────────────────────────────

@Composable
private fun NetworkSummaryBanner(
    totalPeers: Int,
    isConnected: Boolean,
    colorScheme: ColorScheme
) {
    val bannerColor = when {
        totalPeers > 0 -> NeonGreen.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val textColor = when {
        isConnected && totalPeers > 0 -> NeonGreen
        else -> colorScheme.onSurface.copy(alpha = 0.4f)
    }
    val summaryText = when {
        totalPeers == 0 -> "No peers connected"
        totalPeers == 1 -> "1 peer connected"
        else -> "$totalPeers peers connected"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = bannerColor,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, textColor.copy(alpha = 0.3f)
        )
    ) {
        Text(
            text = summaryText,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = textColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

// ── Section header ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String, colorScheme: ColorScheme) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        color = colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 4.dp)
    )
}

// ── Empty hint ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyHint(text: String, colorScheme: ColorScheme) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        color = colorScheme.onSurface.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 10.dp)
    )
}

// ── Individual peer row ───────────────────────────────────────────────────────

@Composable
private fun PeerRow(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    isDirect: Boolean,
    colorScheme: ColorScheme
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // BT / Relay icon
            Icon(
                imageVector = icon,
                contentDescription = if (isDirect) "Direct BLE" else "Relay peer",
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )

            // Peer name in theme color
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = iconTint,
                modifier = Modifier.weight(1f)
            )

            // Connection type label
            Text(
                text = if (isDirect) "direct" else "relay",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = colorScheme.onSurface.copy(alpha = 0.35f),
                fontSize = 10.sp
            )
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 40.dp),
        color = colorScheme.outline.copy(alpha = 0.15f),
        thickness = 0.5.dp
    )
}
