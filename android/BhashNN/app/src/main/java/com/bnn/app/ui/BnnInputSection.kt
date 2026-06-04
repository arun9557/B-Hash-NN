package com.bnn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnn.app.ui.theme.NeonGreen
import com.bnn.app.ui.theme.SurfaceDark2

/**
 * BnnInputSection — message input bar pinned to bottom of screen.
 * Terminal-style: dark surface bg, neon green cursor, monospace hint text.
 */
@Composable
fun BnnInputSection(
    onSend: (String) -> Unit,
    colorScheme: ColorScheme
) {
    var messageText by remember { mutableStateOf("") }

    val canSend = messageText.trim().isNotEmpty()

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
                    if (messageText.isEmpty()) {
                        Text(
                            text = "Ask the AI…",
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

                // ── Send button ─────────────────────────────────────────
                Surface(
                    onClick = {
                        if (canSend) {
                            onSend(messageText.trim())
                            messageText = ""
                        }
                    },
                    shape = RoundedCornerShape(50),
                    color = if (canSend) NeonGreen else NeonGreen.copy(alpha = 0.15f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) Color.Black else NeonGreen.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
