package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
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
    private val hostCandidates = listOf("192.168.2.101", "192.168.1.150")
    private val port = 5555

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

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(status = "Erabiltzailea bidaltzen") }
                    }
                    w.write(currentUserName)
                    w.newLine()
                    w.flush()

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
                        withContext(Dispatchers.Main) {
                            _uiState.update { state ->
                                val isSystem =
                                    line.contains("sartu da", ignoreCase = true) ||
                                        line.contains("atera egin da", ignoreCase = true)
                                val newUnread =
                                    when {
                                        isChatOpen -> 0
                                        isSystem -> state.unreadCount
                                        else -> state.unreadCount + 1
                                    }
                                state.copy(messages = state.messages + line, unreadCount = newUnread)
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



    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty()) return

        if (!_uiState.value.isConnected) {
            pending.addLast(msg)
            connect()
            _uiState.update { it.copy(status = "Konektatzen...") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val w = writer ?: throw IllegalStateException("Writer no disponible")
                w.write(msg)
                w.newLine()
                w.flush()
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
