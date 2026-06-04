package com.bnn.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bnn.app.BnnSettings
import com.bnn.app.BnnThemePreference
import com.bnn.app.ThemeOption
import com.bnn.app.ui.theme.NeonGreen

/**
 * BnnAboutSheet — Settings/About bottom sheet.
 * Matches bitchat screenshot:
 *  - APPEARANCE section: feature cards (Offline Mesh Chat, etc.)
 *  - THEME section: System | Light | Dark segmented toggle
 *  - SETTINGS section: Run in Background toggle
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BnnAboutSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit
) {
    if (!isPresented) return

    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val currentTheme by BnnThemePreference.theme.collectAsStateWithLifecycle()
    val runInBackground by BnnSettings.runInBackground.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = NeonGreen.copy(alpha = 0.4f)
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp)
        ) {
            // ── Close button row ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    color = colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "✕",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Subtitle ──────────────────────────────────────────────────
            Text(
                text = "decentralized AI mesh with Bluetooth LE",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ══════════════════════════════════════════════════════════════
            //  APPEARANCE section
            // ══════════════════════════════════════════════════════════════
            SectionLabel("APPEARANCE")
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    FeatureRow(
                        icon = Icons.Filled.Bluetooth,
                        title = "Offline Mesh Chat",
                        description = "Communicate directly via Bluetooth LE without internet. Messages relay through nearby devices to extend range.",
                        colorScheme = colorScheme
                    )
                    SettingsDivider()
                    FeatureRow(
                        icon = Icons.Filled.Public,
                        title = "AI-Powered Queries",
                        description = "Send prompts to the B#NN server running Ollama. Responses are delivered over the Bluetooth mesh.",
                        colorScheme = colorScheme
                    )
                    SettingsDivider()
                    FeatureRow(
                        icon = Icons.Filled.Lock,
                        title = "Offline & Private",
                        description = "No internet required. All data stays on device and within your local mesh network.",
                        colorScheme = colorScheme
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ══════════════════════════════════════════════════════════════
            //  THEME section
            // ══════════════════════════════════════════════════════════════
            SectionLabel("THEME")
            Spacer(modifier = Modifier.height(8.dp))

            ThemeSegmentedControl(
                selected = currentTheme,
                onSelect = { BnnThemePreference.set(it, context) },
                colorScheme = colorScheme
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ══════════════════════════════════════════════════════════════
            //  SETTINGS section
            // ══════════════════════════════════════════════════════════════
            SectionLabel("SETTINGS")
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsToggleRow(
                        icon = Icons.Filled.Bluetooth,
                        title = "run in background",
                        description = "keep mesh active when app is closed (foreground service)",
                        checked = runInBackground,
                        onCheckedChange = { BnnSettings.setRunInBackground(it, context) },
                        colorScheme = colorScheme
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Version footer ────────────────────────────────────────────
            Text(
                text = "B#NN v1.0 · B Hash Neural Network",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = colorScheme.onSurface.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    description: String,
    colorScheme: ColorScheme
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NeonGreen,
            modifier = Modifier
                .size(22.dp)
                .padding(top = 2.dp)
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = NeonGreen
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colorScheme: ColorScheme
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NeonGreen,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = NeonGreen
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = NeonGreen,
                uncheckedThumbColor = colorScheme.onSurface.copy(alpha = 0.4f),
                uncheckedTrackColor = colorScheme.onSurface.copy(alpha = 0.15f)
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
        thickness = 0.5.dp
    )
}

// ── Segmented theme control (System | Light | Dark) ───────────────────────────

@Composable
private fun ThemeSegmentedControl(
    selected: ThemeOption,
    onSelect: (ThemeOption) -> Unit,
    colorScheme: ColorScheme
) {
    val options = listOf(
        ThemeOption.SYSTEM to "system",
        ThemeOption.LIGHT  to "light",
        ThemeOption.DARK   to "dark"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (option, label) ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) NeonGreen else Color.Transparent
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected) Color.Black else colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
