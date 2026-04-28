package com.example.osislogin.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: ChatViewModel, onLogout: () -> Unit, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val mainScope = rememberCoroutineScope()
    val ioScope = remember { CoroutineScope(Dispatchers.IO) }
    var pendingDownload by remember { mutableStateOf<ChatAttachment?>(null) }

    val saveFileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val attachment = pendingDownload
            if (uri == null || attachment == null) {
                pendingDownload = null
                return@rememberLauncherForActivityResult
            }

            ioScope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(attachment.bytes)
                    } ?: error("Ezin izan da fitxategia gorde")
                }.onSuccess {
                    mainScope.launch {
                        pendingDownload = null
                    }
                }.onFailure {
                    mainScope.launch {
                        pendingDownload = null
                        Toast.makeText(context, "Errorea fitxategia gordetzean", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            ioScope.launch {
                val selectedFile = readAttachment(context, uri)
                mainScope.launch {
                    if (selectedFile == null) {
                        Toast.makeText(context, "Ezin izan da fitxategia irakurri", Toast.LENGTH_SHORT).show()
                    } else if (selectedFile.bytes.size > MAX_CHAT_FILE_SIZE_BYTES) {
                        Toast.makeText(context, "Aukeratutako fitxategia handiegia da", Toast.LENGTH_SHORT).show()
                    } else {
                        val base64 = Base64.encodeToString(selectedFile.bytes, Base64.NO_WRAP)
                        viewModel.sendFile(selectedFile.fileName, base64)
                    }
                }
            }
        }

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

    fun parseAttachment(text: String): ChatAttachment? {
        if (!text.startsWith(FILE_PREFIX)) return null
        val parts = text.split('|', limit = 3)
        if (parts.size != 3) return null
        val bytes =
            runCatching { Base64.decode(parts[2], Base64.DEFAULT) }
                .getOrNull()
                ?: return null
        return ChatAttachment(fileName = parts[1], bytes = bytes)
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
                            val attachment = parseAttachment(text)
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
                                                text = attachment?.let { "\uD83D\uDCCE ${it.fileName}" } ?: text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isMine) ownMessageColor else otherMessageColor,
                                                textAlign = if (isMine) TextAlign.End else TextAlign.Start,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            if (attachment != null) {
                                                Button(
                                                    onClick = {
                                                        pendingDownload = attachment
                                                        saveFileLauncher.launch(attachment.fileName)
                                                    }
                                                ) {
                                                    Text("Gorde")
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
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Fitxategia erantsi",
                        tint = Color(0xFF1C5F2B)
                    )
                }
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

private data class ChatAttachment(
    val fileName: String,
    val bytes: ByteArray
)

private data class SelectedChatFile(
    val fileName: String,
    val bytes: ByteArray
)

private const val FILE_PREFIX = "[FILE]|"
private const val MAX_CHAT_FILE_SIZE_BYTES = 5 * 1024 * 1024

private fun readAttachment(context: android.content.Context, uri: Uri): SelectedChatFile? {
    val resolver = context.contentResolver
    val fileName =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: "fitxategia"

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return SelectedChatFile(fileName = fileName, bytes = bytes)
}
