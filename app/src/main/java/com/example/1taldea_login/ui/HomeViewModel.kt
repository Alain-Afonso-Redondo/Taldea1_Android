package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.ConnectException
import java.net.SocketTimeoutException

data class Mahaia(
    val id: Int,
    val label: String,
    val pertsonaMax: Int,
    val pertsonaMaxRaw: Int,
    val isOccupied: Boolean,
    val erreserbaId: Int?,
    val pertsonaKopurua: Int?
)

data class HomeUiState(
    val tables: List<Mahaia> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val reservationsError: String? = null,
    val debug: String? = null
)

class HomeViewModel(private val sessionManager: SessionManager) : ViewModel() {

    private val apiBaseUrlLanPrimary = "http://192.168.2.101:5000/api"

    val userEmail = sessionManager.userEmail.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val userName = sessionManager.userName.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    fun loadTables() {
        viewModelScope.launch {
            try {
                _uiState.value =
                    _uiState.value.copy(isLoading = true, error = null, reservationsError = null, debug = null)

                val tablesResult = withContext(Dispatchers.IO) { fetchMahaiakFromApi() }
                val baseTables = tablesResult.tables

                var reservationsError: String? = null
                val occupiedByMahaiId =
                    withContext(Dispatchers.IO) {
                        try {
                            fetchOccupiedTablesFromErreserbak()
                        } catch (e: Exception) {
                            reservationsError = e.message ?: e.javaClass.simpleName
                            emptyMap()
                        }
                    }

                val merged =
                    baseTables.map { table ->
                        val occ = occupiedByMahaiId[table.id]
                        if (occ == null) {
                            table
                        } else {
                            table.copy(
                                isOccupied = true,
                                erreserbaId = occ.erreserbaId,
                                pertsonaKopurua = occ.pertsonaKopurua
                            )
                        }
                    }

                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        tables = merged,
                        reservationsError = reservationsError,
                        debug = tablesResult.debug
                    )
            } catch (e: Exception) {
                val isConnectError = e is ConnectException ||
                    e is SocketTimeoutException ||
                    (e.message?.contains("failed to connect", ignoreCase = true) == true)
                val suffix = if (isConnectError) {
                    " (revisa Wi‑Fi/subred, servidor escuchando 0.0.0.0:5000 y firewall)"
                } else {
                    ""
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Errorea mahaiak kargatzean $suffix: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            sessionManager.clearSession()
        }
    }

    private data class TablesFetchResult(val tables: List<Mahaia>, val debug: String)

    private fun fetchMahaiakFromApi(): TablesFetchResult {
        var lastNon2xx: Exception? = null
        var lastOtherException: Exception? = null
        var lastDebug = ""
        var lastOkEmptyUrl: String? = null
        var lastOkEmptyBody: String? = null

        for (baseUrl in apiBaseUrlCandidates()) {
            val candidateUrls =
                listOf(
                        "$baseUrl/mahaiak",
                        "$baseUrl/Mahai",
                        "$baseUrl/Mahaiak",
                        "$baseUrl/api/mahaiak",
                        "$baseUrl/api/Mahai",
                        "$baseUrl/api/Mahaiak"
                ).distinct()

            for (candidateUrl in candidateUrls) {
                try {
                    val url = URL(candidateUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream.bufferedReader().use { it.readText() }

                    if (code !in 200..299) {
                        lastNon2xx = IllegalStateException("HTTP $code $candidateUrl helbidean body=${body.take(250)}")
                        lastDebug = "url=$candidateUrl code=$code body=${body.take(250)}"
                        continue
                    }

                    val tables = parseTables(body)
                    val sample =
                        tables.take(8).joinToString(separator = ", ") { "${it.label}:${it.pertsonaMaxRaw}->${it.pertsonaMax}" }
                    lastDebug = "url=$candidateUrl code=$code sample=[$sample] body=${body.take(250)}"

                    if (tables.isNotEmpty()) return TablesFetchResult(tables = tables, debug = lastDebug)

                    lastOkEmptyUrl = candidateUrl
                    lastOkEmptyBody = body.take(250)
                } catch (e: Exception) {
                    lastOtherException =
                        IllegalStateException(
                            "Ezin izan da konektatu $candidateUrl: ${e.message ?: e.javaClass.simpleName}",
                            e
                        )
                    lastDebug = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        if (lastOkEmptyUrl != null) {
            return TablesFetchResult(
                tables = emptyList(),
                debug = "url=$lastOkEmptyUrl code=200 sample=[] body=${lastOkEmptyBody.orEmpty()}"
            )
        }

        throw lastNon2xx ?: lastOtherException ?: IllegalStateException("Ezin izan dira mahaiak kargatu ($lastDebug)")
    }

    private data class OccupiedInfo(val erreserbaId: Int?, val pertsonaKopurua: Int?)

    private fun normalizeTxanda(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val lower = value.lowercase()
        return when {
            lower.contains("baz") || lower.contains("com") -> "bazkaria"
            lower.contains("afa") || lower.contains("cen") -> "afaria"
            else -> lower
        }
    }

    private fun currentTxandaNormalized(): String {
        val now = java.util.Date()
        val calendar = java.util.Calendar.getInstance().apply { time = now }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour in 12..18) "bazkaria" else "afaria"
    }

    private fun fetchOccupiedTablesFromErreserbak(): Map<Int, OccupiedInfo> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Erreserbak/gaur",
                    "$baseUrl/erreserbak/gaur",
                    "$baseUrl/api/Erreserbak/gaur",
                    "$baseUrl/api/erreserbak/gaur"
                )
            }.distinct()

        var lastException: Exception? = null
        var lastDebug = ""

        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream.bufferedReader().use { it.readText() }

                if (code !in 200..299) {
                    lastException = IllegalStateException("HTTP $code $candidateUrl helbidean body=${body.take(250)}")
                    lastDebug = "url=$candidateUrl code=$code body=${body.take(250)}"
                    continue
                }

                lastDebug = "url=$candidateUrl code=$code body=${body.take(250)}"
                return parseOccupiedFromErreserbak(body)
            } catch (e: Exception) {
                lastException = IllegalStateException("Ezin izan da konektatu $candidateUrl: ${e.message ?: e.javaClass.simpleName}", e)
                lastDebug = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        return emptyMap()
    }

    private fun parseOccupiedFromErreserbak(body: String): Map<Int, OccupiedInfo> {
        val root = JSONTokener(body).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject ->
                root.optJSONArray("erreserbak")
                    ?: root.optJSONArray("Erreserbak")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("result")
                    ?: run {
                        val keys = root.keys()
                        var firstArray: JSONArray? = null
                        while (keys.hasNext() && firstArray == null) {
                            val key = keys.next()
                            firstArray = root.optJSONArray(key)
                        }
                        firstArray ?: JSONArray()
                    }
            else -> JSONArray()
        }

        val bestByMahaiId = LinkedHashMap<Int, OccupiedInfo>()
        val desiredTxanda = currentTxandaNormalized()

        for (i in 0 until array.length()) {
            val erreserba = array.optJSONObject(i) ?: continue
            val erreserbaTxanda =
                normalizeTxanda(erreserba.optString("txanda", erreserba.optString("Txanda", "")))
            if (erreserbaTxanda != null && erreserbaTxanda != desiredTxanda) continue
            val erreserbaId = erreserba.optInt("id", erreserba.optInt("Id", -1)).takeIf { it > 0 }
            val pertsonaKopurua =
                erreserba.optInt("pertsonaKopurua", erreserba.optInt("PertsonaKopurua", -1)).takeIf { it > 0 }
            val mahaiak = erreserba.optJSONArray("mahaiak") ?: erreserba.optJSONArray("Mahaiak") ?: JSONArray()
            for (m in 0 until mahaiak.length()) {
                val mahaiObj = mahaiak.optJSONObject(m) ?: continue
                val mahaiId = mahaiObj.optInt("id", mahaiObj.optInt("Id", -1)).takeIf { it > 0 } ?: continue

                val candidate = OccupiedInfo(erreserbaId = erreserbaId, pertsonaKopurua = pertsonaKopurua)
                val existing = bestByMahaiId[mahaiId]
                if (existing == null) {
                    bestByMahaiId[mahaiId] = candidate
                } else {
                    val existingKop = existing.pertsonaKopurua ?: -1
                    val newKop = candidate.pertsonaKopurua ?: -1
                    if (newKop > existingKop) bestByMahaiId[mahaiId] = candidate
                }
            }
        }

        return bestByMahaiId
    }

    private fun parseTables(body: String): List<Mahaia> {
        val root = JSONTokener(body).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                root.optJSONArray("tables")
                    ?: root.optJSONArray("mahaiak")
                    ?: root.optJSONArray("Mahaiak")
                    ?: root.optJSONArray("mesas")
                    ?: root.optJSONArray("Mesas")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("result")
                    ?: run {
                        val keys = root.keys()
                        var firstArray: JSONArray? = null
                        while (keys.hasNext() && firstArray == null) {
                            val key = keys.next()
                            firstArray = root.optJSONArray(key)
                        }
                        firstArray ?: JSONArray()
                    }
            }
            else -> JSONArray()
        }

        val result = ArrayList<Mahaia>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue

            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: (i + 1)
            val pertsonaMaxRaw = when {
                obj.has("pertsona_max") -> obj.optInt("pertsona_max", -1)
                obj.has("pertsonaMax") -> obj.optInt("pertsonaMax", -1)
                obj.has("PertsonaMax") -> obj.optInt("PertsonaMax", -1)
                obj.has("Pertsona_Max") -> obj.optInt("Pertsona_Max", -1)
                else -> -1
            }
            val pertsonaMax = pertsonaMaxRaw.takeIf { it > 0 } ?: 4

            val label =
                obj.optString(
                        "numero",
                        obj.optString(
                                "zenbakia",
                                obj.optString(
                                        "mahaiZenbakia",
                                        obj.optString(
                                                "MahaiZenbakia",
                                                obj.optString(
                                                        "mahaia",
                                                        obj.optString(
                                                                "Mahaia",
                                                                obj.optString(
                                                                        "id",
                                                                        obj.optString("Id", id.toString())
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ).trim().ifBlank { id.toString() }

            val isOccupied = when {
                obj.has("okupatuta") -> obj.optBoolean("okupatuta")
                obj.has("occupied") -> obj.optBoolean("occupied")
                obj.has("isOccupied") -> obj.optBoolean("isOccupied")
                obj.has("libre") -> !obj.optBoolean("libre")
                obj.has("isFree") -> !obj.optBoolean("isFree")
                else -> false
            }

            val erreserbaIdRaw = when {
                obj.has("erreserbaId") -> obj.optInt("erreserbaId", -1)
                obj.has("erreserba_id") -> obj.optInt("erreserba_id", -1)
                obj.has("ErreserbaId") -> obj.optInt("ErreserbaId", -1)
                else -> -1
            }
            val erreserbaId = erreserbaIdRaw.takeIf { it > 0 }

            val pertsonaKopuruaRaw = when {
                obj.has("pertsonaKopurua") -> obj.optInt("pertsonaKopurua", -1)
                obj.has("pertsona_kopurua") -> obj.optInt("pertsona_kopurua", -1)
                obj.has("PertsonaKopurua") -> obj.optInt("PertsonaKopurua", -1)
                else -> -1
            }
            val pertsonaKopurua = pertsonaKopuruaRaw.takeIf { it > 0 }

            result.add(
                Mahaia(
                    id = id,
                    label = label,
                    pertsonaMax = pertsonaMax,
                    pertsonaMaxRaw = pertsonaMaxRaw,
                    isOccupied = isOccupied,
                    erreserbaId = erreserbaId,
                    pertsonaKopurua = pertsonaKopurua
                )
            )
        }

        return result
    }

    private fun apiBaseUrlCandidates(): List<String> {
        val base = apiBaseUrlLanPrimary.trimEnd('/')
        val noApi =
            if (base.endsWith("/api")) {
                base.removeSuffix("/api").trimEnd('/')
            } else {
                base
            }
        return listOf(base, noApi).distinct()
    }
}

data class KomandaDoneEvent(
    val mahaiId: Int,
    val mahaiLabel: String,
    val newDoneCount: Int,
    val message: String
)

class KomandaDoneNotifierViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.2.101:5000/api"
    private val _events = MutableSharedFlow<KomandaDoneEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<KomandaDoneEvent> = _events

    private var monitorJob: Job? = null
    private val fakturaIdByMahaiId = LinkedHashMap<Int, Int>()
    private val doneKomandaIdsByMahaiId = LinkedHashMap<Int, Set<Int>>()
    private var consecutiveEmptyOccupiedPolls: Int = 0
    private var consecutiveNoSessionPolls: Int = 0

    fun start() {
        if (monitorJob != null) return
        consecutiveEmptyOccupiedPolls = 0
        consecutiveNoSessionPolls = 0
        monitorJob =
            viewModelScope.launch {
                while (isActive) {
                    try {
                        pollOnce()
                    } catch (_: Exception) {
                    }
                    delay(2500)
                }
            }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        fakturaIdByMahaiId.clear()
        doneKomandaIdsByMahaiId.clear()
        consecutiveEmptyOccupiedPolls = 0
        consecutiveNoSessionPolls = 0
    }

    private fun apiBaseUrlCandidates(): List<String> {
        val base = apiBaseUrlLanPrimary.trimEnd('/')
        val noApi =
            if (base.endsWith("/api")) {
                base.removeSuffix("/api").trimEnd('/')
            } else {
                base
            }
        return listOf(base, noApi).distinct()
    }

    private fun currentTxandaNormalized(): String {
        val now = java.util.Date()
        val calendar = java.util.Calendar.getInstance().apply { time = now }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour in 12..18) "bazkaria" else "afaria"
    }

    private fun normalizeTxanda(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val lower = value.lowercase()
        return when {
            lower.contains("baz") || lower.contains("com") -> "bazkaria"
            lower.contains("afa") || lower.contains("cen") -> "afaria"
            else -> lower
        }
    }

    private fun fetchMahaiLabels(): Map<Int, String> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/mahaiak",
                    "$baseUrl/Mahaiak",
                    "$baseUrl/api/mahaiak",
                    "$baseUrl/api/Mahaiak"
                )
            }.distinct()

        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) continue

                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                val array =
                    when (root) {
                        is JSONArray -> root
                        is JSONObject ->
                            root.optJSONArray("mahaiak")
                                ?: root.optJSONArray("Mahaiak")
                                ?: root.optJSONArray("mesas")
                                ?: root.optJSONArray("Mesas")
                                ?: root.optJSONArray("data")
                                ?: root.optJSONArray("result")
                                ?: JSONArray()
                        else -> JSONArray()
                    }

                val result = LinkedHashMap<Int, String>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
                    val label =
                        obj.optString(
                            "numero",
                            obj.optString(
                                "zenbakia",
                                obj.optString(
                                    "mahaiZenbakia",
                                    obj.optString(
                                        "MahaiZenbakia",
                                        obj.optString(
                                            "mahaia",
                                            obj.optString(
                                                "Mahaia",
                                                obj.optString(
                                                    "id",
                                                    obj.optString("Id", id.toString())
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        ).trim().ifBlank { id.toString() }
                    result[id] = label
                }
                return result
            } catch (_: Exception) {
            }
        }
        return emptyMap()
    }

    private fun fetchOccupiedMahaiIds(): Set<Int> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Erreserbak/gaur",
                    "$baseUrl/erreserbak/gaur",
                    "$baseUrl/api/Erreserbak/gaur",
                    "$baseUrl/api/erreserbak/gaur"
                )
            }.distinct()

        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) continue

                val root = JSONTokener(body).nextValue()
                val array =
                    when (root) {
                        is JSONArray -> root
                        is JSONObject ->
                            root.optJSONArray("erreserbak")
                                ?: root.optJSONArray("Erreserbak")
                                ?: root.optJSONArray("data")
                                ?: root.optJSONArray("result")
                                ?: JSONArray()
                        else -> JSONArray()
                    }

                val result = LinkedHashSet<Int>()
                val desiredTxanda = currentTxandaNormalized()
                for (i in 0 until array.length()) {
                    val erreserba = array.optJSONObject(i) ?: continue
                    val erreserbaTxanda =
                        normalizeTxanda(erreserba.optString("txanda", erreserba.optString("Txanda", "")))
                    if (erreserbaTxanda != null && erreserbaTxanda != desiredTxanda) continue
                    val mahaiak = erreserba.optJSONArray("mahaiak") ?: erreserba.optJSONArray("Mahaiak") ?: JSONArray()
                    for (m in 0 until mahaiak.length()) {
                        val mahaiObj = mahaiak.optJSONObject(m) ?: continue
                        val mahaiId = mahaiObj.optInt("id", mahaiObj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
                        result.add(mahaiId)
                    }
                }
                return result
            } catch (_: Exception) {
            }
        }
        return emptySet()
    }

    private fun ensureFakturaIdForMahai(mahaiId: Int): Int? {
        val legacyCandidates =
            listOf(
                "$apiBaseUrlLanPrimary/mahaiak/$mahaiId/comanda-session",
                "$apiBaseUrlLanPrimary/Mahaiak/$mahaiId/comanda-session"
            )

        for (candidateUrl in legacyCandidates) {
            try {
                val url = URL(candidateUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val payload = JSONObject().toString()
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) continue
                val obj = JSONTokener(body).nextValue() as? JSONObject ?: JSONObject(body)
                val fakturaId = obj.optInt("fakturaId", obj.optInt("FakturaId", -1))
                if (fakturaId > 0) return fakturaId
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchDoneKomandaIds(fakturaId: Int): Set<Int> {
        val candidates =
            apiBaseUrlCandidates().flatMap { base ->
                listOf(
                    "$base/Komandak/faktura/$fakturaId",
                    "$base/komandak/faktura/$fakturaId",
                    "$base/komandak/faktura/$fakturaId/items",
                    "$base/Komandak/faktura/$fakturaId/items"
                )
            }.distinct()

        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) continue

            val root = JSONTokener(body).nextValue()
            val array =
                when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("komandak") ?: root.optJSONArray("Komandak") ?: JSONArray()
                    else -> JSONArray()
                }

            val doneIds = LinkedHashSet<Int>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
                val egoeraRaw =
                    when {
                        obj.has("egoera") && !obj.isNull("egoera") -> obj.opt("egoera")
                        obj.has("Egoera") && !obj.isNull("Egoera") -> obj.opt("Egoera")
                        else -> null
                    }
                val egoera =
                    when (egoeraRaw) {
                        is Boolean -> if (egoeraRaw) 1 else 0
                        is Int -> egoeraRaw
                        is Number -> egoeraRaw.toInt()
                        is String -> {
                            val cleaned = egoeraRaw.trim()
                            cleaned.toIntOrNull()
                                ?: if (cleaned.equals("true", ignoreCase = true)) 1 else 0
                        }
                        else -> 0
                    }
                if (egoera != 0) doneIds.add(id)
            }
            return doneIds
        }
        return emptySet()
    }

    private suspend fun pollOnce() {
        val mahaiLabels = fetchMahaiLabels()
        val occupiedMahaiIds = fetchOccupiedMahaiIds()

        if (occupiedMahaiIds.isEmpty()) {
            consecutiveEmptyOccupiedPolls += 1
            if (consecutiveEmptyOccupiedPolls == 6) {
                _events.tryEmit(
                    KomandaDoneEvent(
                        mahaiId = 0,
                        mahaiLabel = "",
                        newDoneCount = 0,
                        message = "Monitor: no detecta mesas ocupadas"
                    )
                )
            }
        } else {
            consecutiveEmptyOccupiedPolls = 0
        }

        val staleIds = fakturaIdByMahaiId.keys.filter { it !in occupiedMahaiIds }
        for (id in staleIds) {
            fakturaIdByMahaiId.remove(id)
            doneKomandaIdsByMahaiId.remove(id)
        }

        var couldNotResolveSessionForAny = false
        for (mahaiId in occupiedMahaiIds) {
            val fakturaId =
                fakturaIdByMahaiId[mahaiId]
                    ?: ensureFakturaIdForMahai(mahaiId)?.also { fakturaIdByMahaiId[mahaiId] = it }
                    ?: continue
            if (!fakturaIdByMahaiId.containsKey(mahaiId)) {
                couldNotResolveSessionForAny = true
            }

            val doneNow = fetchDoneKomandaIds(fakturaId)
            val previous = doneKomandaIdsByMahaiId[mahaiId]
            if (previous == null) {
                doneKomandaIdsByMahaiId[mahaiId] = doneNow
                if (doneNow.isNotEmpty()) {
                    val label = mahaiLabels[mahaiId] ?: mahaiId.toString()
                    val initialCount = doneNow.size
                    val message =
                        if (initialCount == 1) {
                            "Mesa $label: comanda lista"
                        } else {
                            "Mesa $label: $initialCount comandas listas"
                        }
                    _events.tryEmit(
                        KomandaDoneEvent(
                            mahaiId = mahaiId,
                            mahaiLabel = label,
                            newDoneCount = initialCount,
                            message = message
                        )
                    )
                }
                continue
            }

            val newDoneCount = (doneNow - previous).size
            doneKomandaIdsByMahaiId[mahaiId] = doneNow
            if (newDoneCount <= 0) continue

            val label = mahaiLabels[mahaiId] ?: mahaiId.toString()
            val message =
                if (newDoneCount == 1) {
                    "Mesa $label: comanda lista"
                } else {
                    "Mesa $label: $newDoneCount comandas listas"
                }
            _events.tryEmit(
                KomandaDoneEvent(
                    mahaiId = mahaiId,
                    mahaiLabel = label,
                    newDoneCount = newDoneCount,
                    message = message
                )
            )
        }

        if (occupiedMahaiIds.isNotEmpty() && fakturaIdByMahaiId.isEmpty()) {
            consecutiveNoSessionPolls += 1
            if (consecutiveNoSessionPolls == 6) {
                _events.tryEmit(
                    KomandaDoneEvent(
                        mahaiId = 0,
                        mahaiLabel = "",
                        newDoneCount = 0,
                        message = "Monitor: no puede resolver comanda-session para mesas ocupadas"
                    )
                )
            }
        } else if (!couldNotResolveSessionForAny) {
            consecutiveNoSessionPolls = 0
        }
    }
}
