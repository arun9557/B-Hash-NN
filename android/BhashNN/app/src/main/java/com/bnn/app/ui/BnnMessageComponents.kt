package com.bnn.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnn.app.ChatMessage
import com.bnn.app.ui.theme.AiPurple
import com.bnn.app.ui.theme.ErrorRed
import com.bnn.app.ui.theme.NeonGreen
import com.bnn.app.ui.theme.RelayTeal
import com.bnn.app.ui.theme.WarnOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BnnMessagesList — LazyColumn for the message feed.
 * Message styles:
 *   - Outgoing  (user): right-aligned, neon green outline bubble, black text
 *   - Incoming   (AI):  left-aligned,  dark surface bubble, neon green text with "> AI:" prefix
 *   - Relay:            left-aligned,  teal-tinted bubble
 *   - Error (⚠):       left-aligned,  red-tinted bubble
 *   - Loading indicator (…) when AI is thinking
 */
@Composable
fun BnnMessagesList(
    messages: List<ChatMessage>,
    isLoading: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(
            items = messages,
            key = { _, msg -> "${msg.timestamp}_${msg.isOutgoing}" }
        ) { _, msg ->
            MessageBubble(
                message = msg,
                timeFormat = timeFormat,
                colorScheme = colorScheme
            )
        }

        // AI thinking indicator
        if (isLoading) {
            item(key = "typing_indicator") {
                TypingIndicator(colorScheme = colorScheme)
            }
        }
    }
}

// ── Individual message bubble ─────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: ChatMessage,
    timeFormat: SimpleDateFormat,
    colorScheme: ColorScheme
) {
    val isError = message.text.startsWith("⚠")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            when {
                // ── Outgoing (user sent) ────────────────────────────
                message.isOutgoing -> {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 16.dp, topEnd = 4.dp,
                            bottomStart = 16.dp, bottomEnd = 16.dp
                        ),
                        color = NeonGreen.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, NeonGreen.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = NeonGreen,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                // ── Relay message ────────────────────────────────────
                message.isRelay -> {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 4.dp, topEnd = 16.dp,
                            bottomStart = 16.dp, bottomEnd = 16.dp
                        ),
                        color = RelayTeal.copy(alpha = 0.10f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, RelayTeal.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(
                                text = "🔁 relay",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = RelayTeal.copy(alpha = 0.7f),
                                fontSize = 9.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = RelayTeal
                            )
                        }
                    }
                }

                // ── Error message ────────────────────────────────────
                isError -> {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 4.dp, topEnd = 16.dp,
                            bottomStart = 16.dp, bottomEnd = 16.dp
                        ),
                        color = ErrorRed.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, ErrorRed.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = ErrorRed,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                // ── Incoming (AI response) ───────────────────────────
                else -> {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 4.dp, topEnd = 16.dp,
                            bottomStart = 16.dp, bottomEnd = 16.dp
                        ),
                        color = colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, NeonGreen.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            // AI prefix in dimmed green
                            Text(
                                text = "> AI:",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = NeonGreen.copy(alpha = 0.6f),
                                fontSize = 10.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 20.sp
                                ),
                                color = NeonGreen
                            )
                        }
                    }
                }
            }

            // Timestamp
            Text(
                text = timeFormat.format(Date(message.timestamp)),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = colorScheme.onSurface.copy(alpha = 0.3f),
                fontSize = 10.sp,
                modifier = Modifier.padding(
                    start = if (message.isOutgoing) 0.dp else 4.dp,
                    end = if (message.isOutgoing) 4.dp else 0.dp,
                    top = 2.dp
                )
            )
        }
    }
}

// ── AI typing indicator ───────────────────────────────────────────────────────

@Composable
private fun TypingIndicator(colorScheme: ColorScheme) {
    // Pulsing "..." animation
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "typingAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 4.dp, topEnd = 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp
            ),
            color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, NeonGreen.copy(alpha = 0.15f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "> AI is thinking",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = NeonGreen.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = NeonGreen.copy(alpha = alpha),
                    fontSize = 14.sp
                )
            }
        }
    }
}
