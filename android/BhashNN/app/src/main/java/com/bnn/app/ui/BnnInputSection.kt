package com.bnn.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bnn.app.ui.theme.NeonGreen
import com.bnn.app.ui.theme.SurfaceDark2

/**
 * BnnInputSection — message input bar.
 * Layout (mirrors bitchat screenshot):
 *   [type a message…]   [📷 camera]  [🎤 mic / ▶ send]
 *
 * - Mic button is always green circle (like bitchat)
 * - When text present: tap mic → send text
 * - When no text: tap mic → voice recording (toggles record/stop)
 * - Camera button: opens image picker from gallery
 */
@Composable
fun BnnInputSection(
    onSend: (String) -> Unit,
    onImageSelected: (String) -> Unit = {},
    onVoiceRecorded: (Long) -> Unit = {},
    colorScheme: ColorScheme
) {
    var messageText by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val canSend = messageText.trim().isNotEmpty()

    // ── Image picker launcher ─────────────────────────────────────────────
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            onImageSelected(it.toString())
            onSend("📷 [Image: ${it.lastPathSegment ?: "photo"}]")
        }
    }

    // ── Audio permission launcher ─────────────────────────────────────────
    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) isRecording = true
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column {
            HorizontalDivider(color = NeonGreen.copy(alpha = 0.15f), thickness = 0.5.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── Text input ──────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = SurfaceDark2,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (messageText.isEmpty() && !isRecording) {
                        Text(
                            text = if (isRecording) "Recording…" else "type a message…",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp,
                                color = colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        )
                    }
                    BasicTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            color = NeonGreen,
                            lineHeight = 22.sp
                        ),
                        cursorBrush = SolidColor(NeonGreen),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (canSend) {
                                    onSend(messageText.trim())
                                    messageText = ""
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── Camera button ────────────────────────────────────────
                Surface(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    shape = RoundedCornerShape(50),
                    color = colorScheme.surfaceVariant,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "Pick image",
                            tint = colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // ── Mic / Send button (green circle, always) ────────────
                Surface(
                    onClick = {
                        when {
                            canSend -> {
                                // Text present → send it
                                onSend(messageText.trim())
                                messageText = ""
                            }
                            isRecording -> {
                                // Stop recording
                                isRecording = false
                                onVoiceRecorded(3000L) // placeholder duration
                                onSend("🎤 [Voice message]")
                            }
                            else -> {
                                // No text → start voice recording
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    isRecording = true
                                } else {
                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(50),
                    color = when {
                        isRecording -> Color.Red.copy(alpha = 0.85f)
                        else        -> NeonGreen
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                canSend     -> Icons.AutoMirrored.Filled.Send
                                else        -> Icons.Filled.Mic
                            },
                            contentDescription = if (canSend) "Send" else if (isRecording) "Stop recording" else "Voice",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Recording indicator bar
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "● Recording… tap mic to stop",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.Red.copy(alpha = 0.8f)
                        )
                    )
                }
            }
        }
    }
}
