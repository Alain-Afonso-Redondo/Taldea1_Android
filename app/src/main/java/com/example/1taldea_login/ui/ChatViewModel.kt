package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import com.example.osislogin.util.ChatCryptoUtil
import com.example.osislogin.util.ZerbitzariakApiConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ChatUiState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val messages: List<String> = emptyList(),
    val unreadCount: Int = 0
)

class ChatViewModel(userName: String) : ViewModel() {
    private val hostCandidates = ZerbitzariakApiConfig.hostCandidates()
    private val port = ZerbitzariakApiConfig.chatPort

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    private var currentUserName: String = userName
    val userName: String
        get() = currentUserName

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var listenJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0
    private val pending = ArrayDeque<String>()
    private var lastSentFullMessage: String = ""
    @Volatile private var isChatOpen: Boolean = false

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(initialUserName: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(userName = initialUserName) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }

    fun updateUserName(userName: String) {
        val cleaned = userName.trim().ifEmpty { "Anonimo" }
        if (cleaned == currentUserName) return
        currentUserName = cleaned
        if (_uiState.value.isConnected) {
            disconnect()
            connect()
        }
    }

    fun reset() {
        disconnect()
        pending.clear()
        lastSentFullMessage = ""
        _uiState.update { it.copy(messages = emptyList(), unreadCount = 0, error = null, status = "Deskonektatuta") }
    }

    fun setChatOpen(isOpen: Boolean) {
        isChatOpen = isOpen
        if (isOpen) {
            _uiState.update { it.copy(unreadCount = 0) }
        }
    }

    fun connect() {
        if (_uiState.value.isConnecting || _uiState.value.isConnected) return

        reconnectJob?.cancel()
        reconnectJob = null

        _uiState.update {
            it.copy(
                isConnecting = true,
                isConnected = false,
                error = null,
                status = "Konektatzen..."
            )
        }

        listenJob?.cancel()
        listenJob =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    var s: Socket? = null
                    var lastConnectError: Exception? = null
                    for (host in hostCandidates) {
                        try {
                            withContext(Dispatchers.Main) {
                                _uiState.update { it.copy(status = "Konektatzen $host:$port") }
                            }
                            val candidate = Socket()
                            candidate.connect(InetSocketAddress(host, port), 3000)
                            s = candidate
                            break
                        } catch (e: Exception) {
                            lastConnectError = e
                            try {
                                s?.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    val connectedSocket = s ?: throw (lastConnectError ?: IllegalStateException("Ezin izan da konektatu"))
                    if (!connectedSocket.isConnected) {
                        throw (lastConnectError ?: IllegalStateException("Ezin izan da konektatu"))
                    }
                    val r = BufferedReader(InputStreamReader(connectedSocket.getInputStream()))
                    val w = BufferedWriter(OutputStreamWriter(connectedSocket.getOutputStream()))

                    socket = connectedSocket
                    reader = r
                    writer = w

                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(isConnecting = false, isConnected = true, error = null, status = "Konektatuta")
                        }
                    }

                    reconnectAttempts = 0
                    flushPending()

                    while (isActive) {
                        val line = r.readLine() ?: break
                        if (shouldIgnoreIncomingLine(line)) {
                            continue
                        }
                        val displayLine = decryptMessageLine(line)
                        withContext(Dispatchers.Main) {
                            _uiState.update { state ->
                                val isSystem =
                                    displayLine.contains("sartu da", ignoreCase = true) ||
                                        displayLine.contains("atera egin da", ignoreCase = true)
                                val newUnread =
                                    when {
                                        isChatOpen -> 0
                                        isSystem -> state.unreadCount
                                        else -> state.unreadCount + 1
                                    }
                                state.copy(messages = state.messages + displayLine, unreadCount = newUnread)
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isConnecting = false,
                                isConnected = false,
                                error = "${e.javaClass.simpleName}: ${e.message ?: ""}".trimEnd(':', ' '),
                                status = "Deskonektatuta"
                            )
                        }
                    }
                    scheduleReconnect()
                } finally {
                    closeSocket()
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isConnecting = false, isConnected = false, status = "Deskonektatuta") }
                    }
                }
            }
    }

    fun disconnect() {
        listenJob?.cancel()
        listenJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        closeSocket()
        _uiState.update { it.copy(isConnecting = false, isConnected = false, status = "Deskonektatuta") }
    }

    private fun isOwnSystemPresenceMessage(line: String): Boolean {
        val normalizedLine = line.trim()
        val normalizedUser = currentUserName.trim()
        if (normalizedLine.isEmpty() || normalizedUser.isEmpty()) return false

        val isPresenceMessage =
            normalizedLine.contains("sartu da", ignoreCase = true) ||
                normalizedLine.contains("atera egin da", ignoreCase = true)

        if (!isPresenceMessage) return false

        return normalizedLine.contains(normalizedUser, ignoreCase = true)
    }

    private fun shouldIgnoreIncomingLine(line: String): Boolean {
        val normalizedLine = line.trim()
        if (normalizedLine.isEmpty()) return true

        if (normalizedLine.equals(currentUserName.trim(), ignoreCase = true)) {
            return true
        }

        if (isOwnSystemPresenceMessage(normalizedLine)) {
            return true
        }

        val normalizedLastSent = lastSentFullMessage.trim()
        if (normalizedLastSent.isNotEmpty() && normalizedLine == normalizedLastSent) {
            lastSentFullMessage = ""
            return true
        }

        return false
    }

    private fun decryptMessageLine(line: String): String {
        val separatorIndex = line.indexOf(':')
        if (separatorIndex <= 0) return ChatCryptoUtil.decryptIfNeeded(line)

        val author = line.substring(0, separatorIndex).trim()
        val body = line.substring(separatorIndex + 1).trim()
        val decryptedBody = ChatCryptoUtil.decryptIfNeeded(body)
        return "$author: $decryptedBody"
    }



    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty()) return
        sendFormattedMessage(msg)
    }

    fun sendFile(fileName: String, base64Content: String) {
        val cleanName = fileName.trim().replace("|", "_")
        val cleanBase64 = base64Content.trim()
        if (cleanName.isEmpty() || cleanBase64.isEmpty()) return
        sendFormattedMessage("[FILE]|$cleanName|$cleanBase64")
    }

    private fun sendFormattedMessage(messageBody: String) {
        val displayMessage = "${currentUserName.trim()}: $messageBody"
        val fullMessage = "${currentUserName.trim()}: ${ChatCryptoUtil.encrypt(messageBody)}"

        if (!_uiState.value.isConnected) {
            pending.addLast(fullMessage)
            lastSentFullMessage = fullMessage
            connect()
            _uiState.update { it.copy(status = "Konektatzen...") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val w = writer ?: throw IllegalStateException("Writer no disponible")
                w.write(fullMessage)
                w.newLine()
                w.flush()
                lastSentFullMessage = fullMessage
                withContext(Dispatchers.Main) {
                    _uiState.update { state -> state.copy(messages = state.messages + displayMessage) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(error = "${e.javaClass.simpleName}: ${e.message ?: ""}".trimEnd(':', ' '))
                    }
                }
            }
        }
    }

    private suspend fun flushPending() {
        if (pending.isEmpty()) return
        val w = writer ?: return
        while (pending.isNotEmpty()) {
            val msg = pending.removeFirst()
            w.write(msg)
            w.newLine()
        }
        w.flush()
    }

    private fun scheduleReconnect() {
        if (reconnectJob != null) return

        val cappedAttempts = reconnectAttempts.coerceAtMost(4)
        val delayMs = (1000L shl cappedAttempts).coerceAtMost(10000L)
        reconnectAttempts += 1

        reconnectJob =
            viewModelScope.launch {
                delay(delayMs)
                reconnectJob = null
                connect()
            }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        reader = null
        writer = null
    }
}
