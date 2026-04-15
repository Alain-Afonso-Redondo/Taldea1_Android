package com.example.osislogin.ui

import com.example.osislogin.util.SessionManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class Mahaia(
    val id: Int,
    val label: String,
    val pertsonaMax: Int,
    val pertsonaMaxRaw: Int,
    val isOccupied: Boolean,
    val erreserbaId: Int?,
    val pertsonaKopurua: Int?,
    val hasReservation: Boolean
)

data class HomeUiState(
    val tables: List<Mahaia> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val reservationsError: String? = null,
    val debug: String? = null,
    val selectedDateYmd: String = currentDateApiValueStatic(),
    val selectedTxanda: String = currentTxandaApiValueStatic()
)

class HomeViewModel(private val sessionManager: SessionManager) : ViewModel() {

    private val apiBaseUrlLanPrimary = "http://172.16.237.29:5093/api"

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

    fun updateSelectedDate(dateYmd: String) {
        _uiState.value = _uiState.value.copy(selectedDateYmd = dateYmd)
        loadTables()
    }

    fun updateSelectedTxanda(txanda: String) {
        _uiState.value = _uiState.value.copy(selectedTxanda = txanda)
        loadTables()
    }

    fun loadTables() {
        viewModelScope.launch {
            val date = _uiState.value.selectedDateYmd
            val txanda = _uiState.value.selectedTxanda
            try {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    reservationsError = null,
                    debug = null
                )

                val tablesResult = withContext(Dispatchers.IO) { fetchMahaiakFromApi(date, txanda) }
                val reservations = withContext(Dispatchers.IO) { fetchReservations(date, txanda) }
                val occupiedInfo = withContext(Dispatchers.IO) {
                    fetchOccupiedTablesFromActiveEskaerak(
                        tablesResult.tables.filter { it.isOccupied }.map { it.id },
                        date,
                        txanda
                    )
                }

                val reservationsByTableId = reservations.associateBy { it.mahaiaId }
                val merged = tablesResult.tables.map { table ->
                    val reservation = reservationsByTableId[table.id]
                    val occupied = occupiedInfo[table.id]
                    table.copy(
                        erreserbaId = reservation?.id,
                        pertsonaKopurua = occupied?.pertsonaKopurua ?: reservation?.pertsonaKopurua,
                        hasReservation = reservation != null
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    tables = merged,
                    reservationsError = null,
                    debug = tablesResult.debug
                )
            } catch (e: Exception) {
                val isConnectError = e is ConnectException ||
                    e is SocketTimeoutException ||
                    (e.message?.contains("failed to connect", ignoreCase = true) == true)
                val suffix = if (isConnectError) {
                    " (revisa Wi-Fi/subred, servidor escuchando 0.0.0.0:5093 y firewall)"
                } else {
                    ""
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Errorea mahaiak kargatzean$suffix: ${e.message ?: e.javaClass.simpleName}"
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
    private data class OccupiedInfo(val pertsonaKopurua: Int?)
    private data class ReservationInfo(val id: Int, val mahaiaId: Int, val pertsonaKopurua: Int)

    private fun fetchMahaiakFromApi(data: String, txanda: String): TablesFetchResult {
        var lastException: Exception? = null

        for (baseUrl in apiBaseUrlCandidates()) {
            val candidateUrls = listOf(
                "$baseUrl/mahaiak?data=$data&txanda=$txanda",
                "$baseUrl/Mahaiak?data=$data&txanda=$txanda",
                "$baseUrl/mahaiak",
                "$baseUrl/Mahaiak"
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
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                    if (code !in 200..299) {
                        lastException = IllegalStateException("HTTP $code $candidateUrl helbidean: ${body.take(250)}")
                        continue
                    }

                    val tables = parseTables(body)
                    return TablesFetchResult(
                        tables = tables,
                        debug = "url=$candidateUrl code=$code body=${body.take(250)}"
                    )
                } catch (e: Exception) {
                    lastException = IllegalStateException(
                        "Ezin izan da konektatu $candidateUrl: ${e.message ?: e.javaClass.simpleName}",
                        e
                    )
                }
            }
        }

        throw lastException ?: IllegalStateException("Ezin izan dira mahaiak kargatu")
    }

    private fun fetchReservations(data: String, txanda: String): List<ReservationInfo> {
        val normalizedTxanda = txanda.lowercase(Locale.getDefault())
        val candidates = apiBaseUrlCandidates().flatMap { baseUrl ->
            listOf(
                "$baseUrl/Erreserbak/data/$data",
                "$baseUrl/erreserbak/data/$data"
            )
        }.distinct()

        var lastError: String? = null
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
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(250)}"
                    continue
                }

                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                val array = when (root) {
                    is JSONArray -> root
                    is JSONObject ->
                        root.optJSONArray("datuak")
                            ?: root.optJSONArray("Datuak")
                            ?: root.optJSONArray("erreserbak")
                            ?: root.optJSONArray("Erreserbak")
                            ?: JSONArray()
                    else -> JSONArray()
                }

                val result = ArrayList<ReservationInfo>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val objTxanda = obj.optString("txanda", obj.optString("Txanda", "")).trim().lowercase(Locale.getDefault())
                    if (objTxanda.isNotBlank() && objTxanda != normalizedTxanda) continue

                    val id = obj.optInt("id", obj.optInt("Id", -1))
                    val mahaiaId = obj.optInt("mahaiaId", obj.optInt("MahaiaId", -1))
                    val pertsonaKopurua = obj.optInt("pertsonaKopurua", obj.optInt("PertsonaKopurua", -1))
                    if (id > 0 && mahaiaId > 0) {
                        result.add(
                            ReservationInfo(
                                id = id,
                                mahaiaId = mahaiaId,
                                pertsonaKopurua = pertsonaKopurua.takeIf { it > 0 } ?: 1
                            )
                        )
                    }
                }
                return result
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (!lastError.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(reservationsError = lastError)
        }
        return emptyList()
    }

    private fun fetchOccupiedTablesFromActiveEskaerak(tableIds: List<Int>, data: String, txanda: String): Map<Int, OccupiedInfo> {
        if (tableIds.isEmpty()) return emptyMap()

        val result = LinkedHashMap<Int, OccupiedInfo>()

        for (tableId in tableIds) {
            val candidates = apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/eskaerak/mahaia/$tableId/aktiboa?data=$data&txanda=$txanda",
                    "$baseUrl/Eskaerak/mahaia/$tableId/aktiboa?data=$data&txanda=$txanda"
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
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) continue

                    val root = JSONTokener(body).nextValue() as? JSONObject ?: continue
                    val array = root.optJSONArray("datuak") ?: root.optJSONArray("Datuak") ?: JSONArray()
                    val obj = array.optJSONObject(0) ?: continue
                    val komensalak = obj.optInt("Komensalak", obj.optInt("komensalak", -1)).takeIf { it > 0 }
                    result[tableId] = OccupiedInfo(pertsonaKopurua = komensalak)
                    break
                } catch (_: Exception) {
                }
            }
        }

        return result
    }

    private fun parseTables(body: String): List<Mahaia> {
        val root = JSONTokener(body).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                root.optJSONArray("datuak")
                    ?: root.optJSONArray("Datuak")
                    ?: root.optJSONArray("mahaiak")
                    ?: root.optJSONArray("Mahaiak")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("result")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }

        val result = ArrayList<Mahaia>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("Id", obj.optInt("id", -1)).takeIf { it > 0 } ?: continue
            val capacity = obj.optInt("Kapazitatea", obj.optInt("kapazitatea", -1)).takeIf { it > 0 } ?: 4
            val label = obj.optString("Zenbakia", obj.optString("zenbakia", id.toString())).trim().ifBlank { id.toString() }
            val egoera = obj.optString("Egoera", obj.optString("egoera", "")).trim()
            val isOccupied = egoera.equals("okupatuta", ignoreCase = true)

            result.add(
                Mahaia(
                    id = id,
                    label = label,
                    pertsonaMax = capacity,
                    pertsonaMaxRaw = capacity,
                    isOccupied = isOccupied,
                    erreserbaId = null,
                    pertsonaKopurua = null,
                    hasReservation = false
                )
            )
        }

        return result
    }

    private fun apiBaseUrlCandidates(): List<String> {
        val base = apiBaseUrlLanPrimary.trimEnd('/')
        return listOf(base)
    }
}

private fun currentDateApiValueStatic(): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}

private fun currentTxandaApiValueStatic(): String {
    val calendar = Calendar.getInstance()
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    return if (hour in 12..18) "Bazkaria" else "Afaria"
}
