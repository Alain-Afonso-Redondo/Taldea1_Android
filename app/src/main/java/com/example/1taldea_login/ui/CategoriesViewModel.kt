package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.util.SessionManager
import com.example.osislogin.util.ZerbitzariakApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Category(
    val id: Int,
    val name: String
)

data class TableSession(
    val mahaiId: Int,
    val erreserbaMahaiId: Int,
    val erreserbaId: Int?,
    val eskaeraId: Int,
    val fakturaId: Int,
    val fakturaEgoera: Boolean,
    val fakturaTotala: Double,
    val requiresDecision: Boolean,
    val txanda: String,
    val data: String
)

data class ConsumptionLine(val name: String, val qty: Int)

data class CategoriesUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val tableLabel: String? = null,
    val guestCount: Int? = null,
    val session: TableSession? = null,
    val categories: List<Category> = emptyList(),
    val isClosePreviewLoading: Boolean = false,
    val closePreviewFakturaId: Int? = null,
    val closePreviewLines: List<ConsumptionLine> = emptyList()
)

class CategoriesViewModel(
    private val sessionManager: SessionManager
) : ViewModel() {
    private val apiBaseUrlLanPrimary = ZerbitzariakApiConfig.primaryBaseUrl

    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState

    private data class TableInfo(val label: String?, val guestCount: Int?)

    private val plateraNameCache = HashMap<Int, String>()

    fun load(tableId: Int, initialGuestCount: Int, initialErreserbaId: Int?, data: String, txanda: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val tableInfo = withContext(Dispatchers.IO) {
                    runCatching { fetchTableInfoFromMahaiak(tableId) }.getOrElse { TableInfo(label = null, guestCount = null) }
                }
                val activeEskaera = withContext(Dispatchers.IO) { fetchActiveEskaera(tableId, data, txanda) }
                val faktura = withContext(Dispatchers.IO) {
                    activeEskaera?.let { fetchFakturaByEskaeraId(it.eskaeraId) }
                }
                val guestCount =
                    activeEskaera?.komensalak
                        ?: initialGuestCount.takeIf { it > 0 }
                        ?: tableInfo.guestCount
                        ?: fetchGuestCountFromErreserba(initialErreserbaId)
                        ?: 1
                val categories = withContext(Dispatchers.IO) { fetchCategories() }

                val session = TableSession(
                    mahaiId = tableId,
                    erreserbaMahaiId = 0,
                    erreserbaId = activeEskaera?.erreserbaId ?: initialErreserbaId,
                    eskaeraId = activeEskaera?.eskaeraId ?: 0,
                    fakturaId = faktura?.optInt("Id", faktura.optInt("id", 0)) ?: 0,
                    fakturaEgoera = false,
                    fakturaTotala = faktura?.optDouble("Totala", faktura.optDouble("totala", 0.0)) ?: 0.0,
                    requiresDecision = false,
                    txanda = txanda,
                    data = data
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    session = session,
                    tableLabel = tableInfo.label,
                    guestCount = guestCount,
                    categories = categories
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    fun reopenFactura(tableId: Int) {
        val session = _uiState.value.session ?: return
        load(
            tableId = tableId,
            initialGuestCount = _uiState.value.guestCount ?: 1,
            initialErreserbaId = session.erreserbaId,
            data = session.data,
            txanda = session.txanda
        )
    }

    fun closeFactura(eskaeraId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                withContext(Dispatchers.IO) { postOrdainduEskaera(eskaeraId) }
                _uiState.value = _uiState.value.copy(isLoading = false, error = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loadClosePreview(eskaeraId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value =
                    _uiState.value.copy(
                        isClosePreviewLoading = true,
                        closePreviewFakturaId = _uiState.value.session?.fakturaId,
                        closePreviewLines = emptyList(),
                        error = null
                    )
                val lines =
                    withContext(Dispatchers.IO) {
                        fetchConsumptionLinesByEskaera(eskaeraId)
                    }
                _uiState.value = _uiState.value.copy(isClosePreviewLoading = false, closePreviewLines = lines, error = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isClosePreviewLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private data class KomandaItemLite(val platerakId: Int, val kopurua: Int)

    private fun fetchKomandakItemsByFaktura(fakturaId: Int): List<KomandaItemLite> {
        var lastError: String? = null
        val candidates =
            listOf(
                "$apiBaseUrlLanPrimary/komandak/faktura/$fakturaId/items",
                "$apiBaseUrlLanPrimary/Komandak/faktura/$fakturaId/items",
                "$apiBaseUrlLanPrimary/Komandak/faktura/$fakturaId",
                "$apiBaseUrlLanPrimary/komandak/faktura/$fakturaId"
            ).distinct()

        var okBody: String? = null
        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            okBody = body
            break
        }
        val finalBody = okBody ?: throw IllegalStateException("Ezin izan dira kontsumizioak kargatu ($lastError)")
        val root = JSONTokener(finalBody).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("komandak") ?: root.optJSONArray("Komandak") ?: JSONArray()
                else -> JSONArray()
            }
        val result = ArrayList<KomandaItemLite>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val platerakId =
                obj.optInt("platerakId", obj.optInt("PlaterakId", -1)).takeIf { it > 0 }
                    ?: run {
                        val pObj = obj.optJSONObject("platerak") ?: obj.optJSONObject("Platerak")
                        pObj?.optInt("id", pObj.optInt("Id", -1))
                    }
                    ?: -1
            val kopurua = obj.optInt("kopurua", obj.optInt("Kopurua", 0))
            if (platerakId > 0 && kopurua > 0) result.add(KomandaItemLite(platerakId = platerakId, kopurua = kopurua))
        }
        return result
    }

    private fun fetchPlateraName(platerakId: Int): String {
        plateraNameCache[platerakId]?.let { return it }

        val candidates =
            listOf(
                "$apiBaseUrlLanPrimary/Platerak/$platerakId",
                "$apiBaseUrlLanPrimary/platerak/$platerakId"
            )

        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) continue
            val obj = (runCatching { JSONTokener(body).nextValue() }.getOrNull() as? JSONObject) ?: continue
            val name = obj.optString("izena", obj.optString("Izena", platerakId.toString())).trim().ifBlank { platerakId.toString() }
            plateraNameCache[platerakId] = name
            return name
        }
        return platerakId.toString()
    }

    private fun resolveClosedFactura(tableId: Int, action: String) {
        viewModelScope.launch {
            try {
                val currentSession = _uiState.value.session
                    ?: throw IllegalStateException("Ez dago mahaiko saiorik")
                val currentGuestCount = _uiState.value.guestCount ?: 1
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val session = withContext(Dispatchers.IO) {
                    ensureSession(
                        tableId = tableId,
                        initialGuestCount = currentGuestCount,
                        initialErreserbaId = currentSession.erreserbaId,
                        data = currentSession.data,
                        txanda = currentSession.txanda,
                        action = action
                    )
                }
                val tableInfo = withContext(Dispatchers.IO) {
                    runCatching { fetchTableInfoFromMahaiak(tableId) }.getOrElse { TableInfo(label = null, guestCount = null) }
                }
                val guestCount =
                    withContext(Dispatchers.IO) {
                        sessionGuestCount(session, currentGuestCount)
                            ?: currentGuestCount
                            ?: tableInfo.guestCount
                            ?: fetchGuestCountFromErreserba(session.erreserbaId)
                    }
                val categories = withContext(Dispatchers.IO) { fetchCategories() }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        session = session,
                        tableLabel = tableInfo.label,
                        guestCount = guestCount,
                        categories = categories,
                        error = null
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun sessionGuestCount(session: TableSession, fallback: Int?): Int? {
        val active = fetchActiveEskaera(session.mahaiId, session.data, session.txanda)
        return active?.komensalak ?: fallback
    }

    private fun fetchTableInfoFromMahaiak(tableId: Int): TableInfo {
        val candidates =
            listOf(
                "$apiBaseUrlLanPrimary/Mahaiak",
                "$apiBaseUrlLanPrimary/mahaiak",
                apiBaseUrlLanPrimary.removeSuffix("/api").trimEnd('/') + "/api/Mahaiak",
                apiBaseUrlLanPrimary.removeSuffix("/api").trimEnd('/') + "/api/mahaiak"
            ).distinct()

        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) continue
                okBody = body
                break
            } catch (_: Exception) {
            }
        }

        val finalBody = okBody ?: return TableInfo(label = tableId.toString(), guestCount = null)
        val root = runCatching { JSONTokener(finalBody).nextValue() }.getOrNull()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject ->
                    root.optJSONArray("datuak")
                        ?: root.optJSONArray("Datuak")
                        ?: root.optJSONArray("mahaiak")
                        ?: root.optJSONArray("Mahaiak")
                        ?: JSONArray()
                else -> JSONArray()
            }

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            if (id != tableId) continue

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
                                                obj.optString("Id", tableId.toString())
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                    .trim()
                    .ifBlank { tableId.toString() }

            val guestCountRaw =
                when {
                    obj.has("pertsonaKopurua") -> obj.optInt("pertsonaKopurua", -1)
                    obj.has("pertsona_kopurua") -> obj.optInt("pertsona_kopurua", -1)
                    obj.has("PertsonaKopurua") -> obj.optInt("PertsonaKopurua", -1)
                    obj.has("kapazitatea") -> obj.optInt("kapazitatea", -1)
                    obj.has("Kapazitatea") -> obj.optInt("Kapazitatea", -1)
                    else -> -1
                }
            val guestCount = guestCountRaw.takeIf { it > 0 }

            return TableInfo(label = label, guestCount = guestCount)
        }

        return TableInfo(label = tableId.toString(), guestCount = null)
    }

    private fun ensureSession(
        tableId: Int,
        initialGuestCount: Int,
        initialErreserbaId: Int?,
        data: String,
        txanda: String,
        action: String?
    ): TableSession {
        var lastError: String? = null
        val legacyCandidates = listOf(
            "$apiBaseUrlLanPrimary/mahaiak/$tableId/comanda-session",
            "$apiBaseUrlLanPrimary/Mahaiak/$tableId/comanda-session"
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
                val payload = JSONObject().also { obj ->
                    if (!action.isNullOrBlank()) obj.put("action", action)
                }.toString()
                conn.outputStream.use { it.write(payload.toByteArray()) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }

                val obj = JSONTokener(body).nextValue() as? JSONObject ?: JSONObject(body)
                return TableSession(
                    mahaiId = obj.optInt("mahaiId", obj.optInt("MahaiId")),
                    erreserbaMahaiId = obj.optInt("erreserbaMahaiId", obj.optInt("ErreserbaMahaiId")),
                    erreserbaId = obj.optInt("erreserbaId", obj.optInt("ErreserbaId")),
                    eskaeraId = 0,
                    fakturaId = obj.optInt("fakturaId", obj.optInt("FakturaId")),
                    fakturaEgoera = obj.optBoolean("fakturaEgoera", obj.optBoolean("FakturaEgoera", false)),
                    fakturaTotala = obj.optDouble("fakturaTotala", obj.optDouble("FakturaTotala", 0.0)),
                    requiresDecision = obj.optBoolean("requiresDecision", obj.optBoolean("RequiresDecision", false)),
                    txanda = obj.optString("txanda", obj.optString("Txanda")),
                    data = obj.optString("data", obj.optString("Data"))
                )
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val erreserbaId = initialErreserbaId
        var eskaera = fetchActiveEskaera(tableId, data, txanda)
        val effectiveErreserbaId = eskaera?.erreserbaId ?: erreserbaId
        val faktura = eskaera?.let { fetchFakturaByEskaeraId(it.eskaeraId) }
        val fakturaId = faktura?.optInt("id", faktura.optInt("Id", -1))?.takeIf { it > 0 } ?: 0
        val fakturaTotala = faktura?.optDouble("totala", faktura.optDouble("Totala", 0.0)) ?: 0.0

        return TableSession(
            mahaiId = tableId,
            erreserbaMahaiId = 0,
            erreserbaId = effectiveErreserbaId,
            eskaeraId = eskaera?.eskaeraId ?: 0,
            fakturaId = fakturaId,
            fakturaEgoera = false,
            fakturaTotala = fakturaTotala,
            requiresDecision = false,
            txanda = txanda,
            data = data
        )
    }

    private fun fetchErreserbaIdFromMahaiak(tableId: Int): Int? {
        var lastError: String? = null
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/Mahaiak",
            "$apiBaseUrlLanPrimary/mahaiak",
            apiBaseUrlLanPrimary.removeSuffix("/api").trimEnd('/') + "/api/Mahaiak",
            apiBaseUrlLanPrimary.removeSuffix("/api").trimEnd('/') + "/api/mahaiak"
        ).distinct()

        var okBody: String? = null
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
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }
                okBody = body
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val finalBody = okBody ?: return null
        val root = runCatching { JSONTokener(finalBody).nextValue() }.getOrNull()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("mahaiak") ?: root.optJSONArray("Mahaiak") ?: JSONArray()
            else -> JSONArray()
        }

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            if (id != tableId) continue
            val erreserbaId = obj.optInt("erreserbaId", obj.optInt("ErreserbaId", -1)).takeIf { it > 0 }
            return erreserbaId
        }

        if (!lastError.isNullOrBlank()) {
            throw IllegalStateException("Ezin izan da ($lastError). mahaiaren erreserba lortu")
        }
        return null
    }

    private fun fetchGaurkoErreserbak(): JSONArray {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/Erreserbak/gaur",
            "$apiBaseUrlLanPrimary/erreserbak/gaur"
        )

        var lastError: String? = null
        var okBody: String? = null

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
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            okBody = body
            break
        }

        val finalBody = okBody ?: return JSONArray()
        val root = runCatching { JSONTokener(finalBody).nextValue() }.getOrNull() ?: return JSONArray()
        return when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("erreserbak") ?: root.optJSONArray("data") ?: root.optJSONArray("result") ?: JSONArray()
            else -> JSONArray()
        }
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

    private fun findErreserbaForTable(erreserbak: JSONArray, tableId: Int): JSONObject? {
        val desiredTxanda =
            run {
                val now = java.util.Date()
                val calendar = java.util.Calendar.getInstance().apply { time = now }
                val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                if (hour in 12..18) "bazkaria" else "afaria"
            }
        for (i in 0 until erreserbak.length()) {
            val erreserba = erreserbak.optJSONObject(i) ?: continue
            val erreserbaTxanda =
                normalizeTxanda(erreserba.optString("txanda", erreserba.optString("Txanda", "")))
            if (erreserbaTxanda != null && erreserbaTxanda != desiredTxanda) continue
            val mahaiak = erreserba.optJSONArray("mahaiak") ?: erreserba.optJSONArray("Mahaiak") ?: continue
            for (m in 0 until mahaiak.length()) {
                val mahai = mahaiak.optJSONObject(m) ?: continue
                val id = mahai.optInt("id", mahai.optInt("Id", -1))
                if (id == tableId) return erreserba
            }
        }
        return null
    }

    private fun fetchGuestCountFromErreserba(erreserbaId: Int?): Int? {
        if (erreserbaId == null || erreserbaId <= 0) return null
        val candidates =
            listOf(
                "$apiBaseUrlLanPrimary/Erreserbak/$erreserbaId",
                "$apiBaseUrlLanPrimary/erreserbak/$erreserbaId"
            )
        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) continue
                val obj = (runCatching { JSONTokener(body).nextValue() }.getOrNull() as? JSONObject) ?: continue
                val guestCount = obj.optInt("pertsonaKopurua", obj.optInt("PertsonaKopurua", -1))
                return guestCount.takeIf { it > 0 }
            } catch (_: Exception) {
            }
        }

        val erreserbak = fetchGaurkoErreserbak()
        for (i in 0 until erreserbak.length()) {
            val erreserba = erreserbak.optJSONObject(i) ?: continue
            val id = erreserba.optInt("id", erreserba.optInt("Id", -1))
            if (id != erreserbaId) continue
            val guestCount = erreserba.optInt("pertsonaKopurua", erreserba.optInt("PertsonaKopurua", -1))
            return guestCount.takeIf { it > 0 }
        }
        return null
    }

    private data class ActiveEskaeraInfo(
        val eskaeraId: Int,
        val erreserbaId: Int?,
        val komensalak: Int?
    )

    private fun fetchActiveEskaera(tableId: Int, data: String, txanda: String): ActiveEskaeraInfo? {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/eskaerak/mahaia/$tableId/aktiboa?data=$data&txanda=$txanda",
            "$apiBaseUrlLanPrimary/Eskaerak/mahaia/$tableId/aktiboa?data=$data&txanda=$txanda"
        )

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
                if (code == 404) return null
                if (code !in 200..299) continue

                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                val array = when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("datuak") ?: root.optJSONArray("Datuak") ?: JSONArray()
                    else -> JSONArray()
                }
                val obj = array.optJSONObject(0) ?: continue
                val eskaeraId = obj.optInt("id", obj.optInt("Id", -1))
                if (eskaeraId <= 0) continue

                return ActiveEskaeraInfo(
                    eskaeraId = eskaeraId,
                    erreserbaId = obj.optInt("erreserbaId", obj.optInt("ErreserbaId", -1)).takeIf { it > 0 },
                    komensalak = obj.optInt("komensalak", obj.optInt("Komensalak", -1)).takeIf { it > 0 }
                )
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun fetchDefaultGuestCount(tableId: Int, erreserbaId: Int?): Int {
        return fetchGuestCountFromErreserba(erreserbaId)
            ?: runCatching { fetchTableInfoFromMahaiak(tableId).guestCount }.getOrNull()
            ?: 1
    }

    private fun createEmptyEskaera(
        erabiltzaileId: Int,
        mahaiaId: Int,
        komensalak: Int,
        erreserbaId: Int?,
        data: String,
        txanda: String
    ): Int {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/eskaerak",
            "$apiBaseUrlLanPrimary/Eskaerak"
        )

        val payload = JSONObject()
            .put("erabiltzaileId", erabiltzaileId)
            .put("mahaiaId", mahaiaId)
            .put("komensalak", komensalak.coerceAtLeast(1))
            .put("erreserbaId", erreserbaId)
            .put("data", data)
            .put("txanda", txanda)
            .put("produktuak", JSONArray())
            .toString()

        var lastError: String? = null
        for (candidateUrl in candidates) {
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
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }

                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                val array = when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("datuak") ?: root.optJSONArray("Datuak") ?: JSONArray()
                    else -> JSONArray()
                }
                val eskaeraId = array.optInt(0, -1)
                if (eskaeraId > 0) return eskaeraId
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da mahaiko eskaera sortu ($lastError)")
    }

    private fun fetchFakturaByEskaeraId(eskaeraId: Int): JSONObject? {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/fakturak",
            "$apiBaseUrlLanPrimary/Fakturak"
        )

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

                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                val array = when (root) {
                    is JSONArray -> root
                    is JSONObject ->
                        root.optJSONArray("datuak")
                            ?: root.optJSONArray("Datuak")
                            ?: root.optJSONArray("fakturak")
                            ?: root.optJSONArray("Fakturak")
                            ?: JSONArray()
                    else -> JSONArray()
                }

                var best: JSONObject? = null
                var bestId = -1
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val currentEskaeraId = obj.optInt("eskaeraId", obj.optInt("EskaeraId", -1))
                    if (currentEskaeraId != eskaeraId) continue
                    val currentId = obj.optInt("id", obj.optInt("Id", -1))
                    if (currentId > bestId) {
                        best = obj
                        bestId = currentId
                    }
                }
                if (best != null) return best
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun fetchFakturaByErreserba(erreserbaId: Int): JSONObject? {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/Fakturak/erreserba/$erreserbaId/item",
            "$apiBaseUrlLanPrimary/fakturak/erreserba/$erreserbaId/item",
            "$apiBaseUrlLanPrimary/Fakturak/erreserba/$erreserbaId",
            "$apiBaseUrlLanPrimary/fakturak/erreserba/$erreserbaId"
        )

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
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 404) return null
            if (code !in 200..299) continue
            val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
            when (root) {
                is JSONObject -> {
                    val wrapped = root.optJSONArray("datuak") ?: root.optJSONArray("Datuak")
                    if (wrapped != null && wrapped.length() > 0) {
                        return wrapped.optJSONObject(0)
                    }
                    return root
                }
                else -> return null
            }
        }
        return null
    }

    private fun fetchFakturaByErreserbaFromList(erreserbaId: Int): JSONObject? {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/fakturak/items",
            "$apiBaseUrlLanPrimary/Fakturak/items",
            "$apiBaseUrlLanPrimary/fakturak",
            "$apiBaseUrlLanPrimary/Fakturak"
        )

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

                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                val array = when (root) {
                    is JSONArray -> root
                    is JSONObject ->
                        root.optJSONArray("datuak")
                            ?: root.optJSONArray("Datuak")
                            ?: root.optJSONArray("fakturak")
                            ?: root.optJSONArray("Fakturak")
                            ?: root.optJSONArray("data")
                            ?: root.optJSONArray("result")
                            ?: JSONArray()
                    else -> JSONArray()
                }
                var best: JSONObject? = null
                var bestIsOpen = false
                var bestId = -1
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", obj.optInt("Id", -1))
                    if (id <= 0) continue
                    val eId =
                        obj.optInt(
                            "erreserbaId",
                            obj.optInt(
                                "ErreserbaId",
                                obj.optInt("erreserbakId", obj.optInt("ErreserbakId", -1))
                            )
                        )
                    if (eId != erreserbaId) continue

                    val egoera = obj.optBoolean("egoera", obj.optBoolean("Egoera", false))
                    val isOpen = !egoera
                    if (best == null) {
                        best = obj
                        bestIsOpen = isOpen
                        bestId = id
                        continue
                    }
                    if (isOpen && !bestIsOpen) {
                        best = obj
                        bestIsOpen = true
                        bestId = id
                        continue
                    }
                    if (isOpen == bestIsOpen && id > bestId) {
                        best = obj
                        bestId = id
                    }
                }
                if (best != null) return best
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun createFaktura(erreserbaId: Int): JSONObject {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/Fakturak",
            "$apiBaseUrlLanPrimary/fakturak"
        )

        val payload =
            JSONObject()
                .put("totala", 0.0)
                .put("egoera", false)
                .put("fakturaPdf", "")
                .put("erreserbakId", erreserbaId)
                .toString()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                if (code == 400 && body.contains("dagoeneko faktura bat du", ignoreCase = true)) {
                    val existing = fetchFakturaByErreserba(erreserbaId) ?: fetchFakturaByErreserbaFromList(erreserbaId)
                    if (existing != null) return existing
                }
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            return JSONTokener(body).nextValue() as? JSONObject ?: JSONObject(body)
        }

        throw IllegalStateException("Ezin izan da faktura sortu ($lastError)")
    }

    private fun putFaktura(id: Int, totala: Double, egoera: Boolean, fakturaPdf: String, erreserbakId: Int) {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/Fakturak/$id",
            "$apiBaseUrlLanPrimary/fakturak/$id"
        )

        val payload =
            JSONObject()
                .put("id", id)
                .put("totala", totala)
                .put("egoera", egoera)
                .put("fakturaPdf", fakturaPdf)
                .put("erreserbakId", erreserbakId)
                .toString()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) return
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }
        throw IllegalStateException("Ezin izan da faktura eguneratu ($lastError)")
    }

    private fun deleteKomandakForFaktura(fakturaId: Int) {
        val komandak = fetchKomandakByFaktura(fakturaId)
        for (komandaId in komandak) {
            deleteKomanda(komandaId)
        }
    }

    private fun fetchKomandakByFaktura(fakturaId: Int): List<Int> {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/Komandak/faktura/$fakturaId",
            "$apiBaseUrlLanPrimary/komandak/faktura/$fakturaId"
        )

        var okBody: String? = null
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
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) continue
            okBody = body
            break
        }

        val finalBody = okBody ?: return emptyList()
        val root = JSONTokener(finalBody).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("komandak") ?: root.optJSONArray("data") ?: root.optJSONArray("result") ?: JSONArray()
            else -> JSONArray()
        }

        val result = ArrayList<Int>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            if (id > 0) result.add(id)
        }
        return result
    }

    private fun deleteKomanda(komandaId: Int) {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/Komandak/$komandaId",
            "$apiBaseUrlLanPrimary/komandak/$komandaId"
        )

        var lastError: String? = null
        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            if (code in 200..299) return
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }
        throw IllegalStateException("Ezin izan da komanda ezabatu ($lastError)")
    }

    private fun fetchFakturaTotala(fakturaId: Int): Double {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/Fakturak/$fakturaId/totala-kalkulatu",
            "$apiBaseUrlLanPrimary/fakturak/$fakturaId/totala-kalkulatu"
        )

        var lastError: String? = null
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
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }

            val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()
            return when (parsed) {
                is Number -> parsed.toDouble()
                is JSONObject -> parsed.optDouble("totala", parsed.optDouble("Totala", 0.0))
                else -> body.trim().toDoubleOrNull() ?: 0.0
            }
        }

        throw IllegalStateException("Ezin izan da guztira kalkulatu ($lastError)")
    }

    private fun patchOrdaindu(fakturaId: Int) {
        var lastError: String? = null
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/fakturak/$fakturaId/ordaindu-check",
            "$apiBaseUrlLanPrimary/Fakturak/$fakturaId/ordaindu-check",
            "$apiBaseUrlLanPrimary/fakturak/$fakturaId/ordaindu",
            "$apiBaseUrlLanPrimary/Fakturak/$fakturaId/ordaindu"
        )

        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            if (code in 200..299) return

            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }

        throw IllegalStateException("Ezin izan da faktura itxi ($lastError)")
    }

    private fun postOrdainduEskaera(eskaeraId: Int) {
        var lastError: String? = null
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/eskaerak/$eskaeraId/ordainduEskaera",
            "$apiBaseUrlLanPrimary/Eskaerak/$eskaeraId/ordainduEskaera"
        )

        for (candidateUrl in candidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            if (code in 200..299) return

            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }

        throw IllegalStateException("Ezin izan da eskaera ordainketara bidali ($lastError)")
    }

    private fun fetchConsumptionLinesByEskaera(eskaeraId: Int): List<ConsumptionLine> {
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/eskaerak/$eskaeraId/produktuak",
            "$apiBaseUrlLanPrimary/Eskaerak/$eskaeraId/produktuak"
        )

        var lastError: String? = null
        var okBody: String? = null
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
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            okBody = body
            break
        }

        val finalBody = okBody ?: throw IllegalStateException("Ezin izan dira kontsumizioak kargatu ($lastError)")
        val root = JSONTokener(finalBody).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("datuak") ?: root.optJSONArray("Datuak") ?: JSONArray()
            else -> JSONArray()
        }

        val totals = linkedMapOf<String, Int>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name =
                obj.optString(
                    "ProduktuaIzena",
                    obj.optString("produktuaIzena", "")
                ).trim()
            val qty = obj.optInt("Kantitatea", obj.optInt("kantitatea", 0))
            if (name.isBlank() || qty <= 0) continue
            totals[name] = (totals[name] ?: 0) + qty
        }

        return totals.entries
            .map { ConsumptionLine(name = it.key, qty = it.value) }
            .sortedBy { it.name.lowercase() }
    }

    private fun fetchCategories(): List<Category> {
        var lastError: String? = null
        val candidates = listOf(
            "$apiBaseUrlLanPrimary/kategoriak",
            "$apiBaseUrlLanPrimary/Kategoriak"
        )

        var body: String? = null
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
            body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            break
        }

        val finalBody = body ?: throw IllegalStateException("Ezin izan dira kategoriak kargatu ($lastError)")

        val root = JSONTokener(finalBody).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject ->
                root.optJSONArray("datuak")
                    ?: root.optJSONArray("Datuak")
                    ?: root.optJSONArray("kategoriak")
                    ?: root.optJSONArray("Kategoriak")
                    ?: JSONArray()
            else -> JSONArray()
        }

        val result = ArrayList<Category>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            val name = obj.optString("izena", obj.optString("Izena", ""))
            if (id > 0 && name.isNotBlank()) {
                result.add(Category(id = id, name = name))
            }
        }

        return result
    }
}
