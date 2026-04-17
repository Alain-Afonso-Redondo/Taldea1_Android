package com.example.osislogin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(viewModel: ChatViewModel, onLogout: () -> Unit, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        viewModel.setChatOpen(true)
        onDispose { viewModel.setChatOpen(false) }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            delay(50)
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    fun parseAuthor(raw: String): Pair<String?, String> {
        val idx = raw.indexOf(':')
        if (idx <= 0) return null to raw
        val author = raw.substring(0, idx).trim().takeIf { it.isNotEmpty() }
        val text = raw.substring(idx + 1).trim().ifEmpty { raw }
        return author to text
    }

    fun normalizeUserName(value: String?): String {
        return value?.trim()?.lowercase().orEmpty()
    }

    val ownBubbleColor = remember { Color(0xFF1F6F5F) }
    val otherBubbleColor = remember { Color(0xFFF3F0EA) }
    val screenColor = remember { Color(0xFFE9E3D9) }
    val ownAuthorColor = remember { Color(0xFFBEE9DF) }
    val otherAuthorColor = remember { Color(0xFF5B5F73) }
    val ownMessageColor = remember { Color.White }
    val otherMessageColor = remember { Color(0xFF111111) }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        navigationIconContentDescription = "Atzera",
        showRightAction = false
    ) { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize()) {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.status ?: if (uiState.isConnected) "Konektatuta" else if (uiState.isConnecting) "Konektatzen..." else "Deskonektatuta",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        uiState.error?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.connect() }, enabled = !uiState.isConnecting) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Berriro saiatu")
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(screenColor)
            ) {
                if (uiState.messages.isEmpty()) {
                    Text(
                        text = "Ez dago mezurik",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.messages) { raw ->
                            val (author, text) = parseAuthor(raw)
                            val isSystemJoin =
                                raw.contains("sartu da", ignoreCase = true) ||
                                    text.contains("sartu da", ignoreCase = true) ||
                                    raw.contains("atera egin da", ignoreCase = true) ||
                                    text.contains("atera egin da", ignoreCase = true)
                            val isMine =
                                normalizeUserName(author) == normalizeUserName(viewModel.userName)
                            val bubbleColor = if (isMine) ownBubbleColor else otherBubbleColor
                            val arrangement = if (isMine) Arrangement.End else Arrangement.Start
                            val bubbleShape =
                                if (isMine) {
                                    RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                                } else {
                                    RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                                }

                            if (isSystemJoin) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = raw,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF4A4A4A)
                                    )
                                }
                                return@items
                            }

                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val bubbleMaxWidth = maxWidth * 0.80f
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = arrangement
                                ) {
                                    Card(
                                        modifier = Modifier.widthIn(max = bubbleMaxWidth),
                                        shape = bubbleShape,
                                    ) {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .background(bubbleColor)
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (!author.isNullOrBlank() && !isMine) {
                                                Text(
                                                    text = author,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = otherAuthorColor,
                                                    textAlign = TextAlign.Start,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                            if (!author.isNullOrBlank() && isMine) {
                                                Text(
                                                    text = author,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ownAuthorColor,
                                                    textAlign = TextAlign.End,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                            Text(
                                                text = text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isMine) ownMessageColor else otherMessageColor,
                                                textAlign = if (isMine) TextAlign.End else TextAlign.Start,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF7F7F7))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                )
                IconButton(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1C5F2B))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "Bidali",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
