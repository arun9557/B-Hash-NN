package com.bnn.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bnn.app.BnnViewModel
import com.bnn.app.ui.theme.NeonGreen

/**
 * BnnChatScreen — Main Compose screen coordinator.
 * Mirrors bitchat's ChatScreen.kt architecture:
 *   - Floating header (absolute, zIndex=1) at top
 *   - Messages LazyColumn (fills remaining space)
 *   - Input bar pinned to bottom
 *   - Scroll-to-bottom FAB (animated)
 *   - Peer list bottom sheet (triggered via header peer counter)
 */
@Composable
fun BnnChatScreen(viewModel: BnnViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val showPeerSheet by viewModel.showPeerSheet.collectAsStateWithLifecycle()
    val showAboutSheet by viewModel.showAboutSheet.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var forceScrollToBottom by remember { mutableStateOf(false) }

    // Detect whether user has scrolled up
    val isScrolledUp by remember {
        derivedStateOf {
            listState.layoutInfo.let { info ->
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                val total = info.totalItemsCount
                total > 0 && last < total - 1
            }
        }
    }

    // Auto-scroll when new message arrives or forced
    LaunchedEffect(messages.size, forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val headerHeight = 56.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // ── Main content column (shifts with keyboard) ──────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            // Space for the floating header
            Spacer(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(headerHeight)
            )

            // Messages list — takes all remaining space
            BnnMessagesList(
                messages = messages,
                isLoading = isLoading,
                listState = listState,
                modifier = Modifier.weight(1f)
            )

            // Input bar pinned at bottom
            BnnInputSection(
                onSend = { text -> viewModel.sendMessage(text) },
                colorScheme = colorScheme
            )
        }

        // ── Floating header (always on top, not affected by keyboard) ───
        BnnFloatingHeader(
            headerHeight = headerHeight,
            viewModel = viewModel,
            colorScheme = colorScheme,
            onPeerCounterClick = { viewModel.showPeerSheet() },
            onTitleClick = { viewModel.showAboutSheet() }
        )

        // ── Divider under header ────────────────────────────────────────
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .offset(y = headerHeight)
                .zIndex(0.5f),
            color = NeonGreen.copy(alpha = 0.2f),
            thickness = 1.dp
        )

        // ── Scroll-to-bottom FAB ────────────────────────────────────────
        AnimatedVisibility(
            visible = isScrolledUp,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 72.dp)
                .zIndex(2f)
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.background,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(2.dp, NeonGreen)
            ) {
                IconButton(
                    onClick = { forceScrollToBottom = !forceScrollToBottom }
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "Scroll to bottom",
                        tint = NeonGreen
                    )
                }
            }
        }
    }

    // ── Peer list bottom sheet ─────────────────────────────────────────────
    if (showPeerSheet) {
        BnnPeerListSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.hidePeerSheet() }
        )
    }

    // ── About/Settings bottom sheet ────────────────────────────────────────
    BnnAboutSheet(
        isPresented = showAboutSheet,
        onDismiss = { viewModel.hideAboutSheet() }
    )
}
