package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

data class Category(
    val id: Int,
    val name: String
)

data class TableSession(
    val mahaiId: Int,
    val erreserbaMahaiId: Int,
    val erreserbaId: Int,
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

class CategoriesViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.2.101:5000/api"

    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState

    private data class TableInfo(val label: String?, val guestCount: Int?)

    private val plateraNameCache = HashMap<Int, String>()

    fun load(tableId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val session = withContext(Dispatchers.IO) { ensureSession(tableId, action = null) }
                val tableInfo = withContext(Dispatchers.IO) {
                    runCatching { fetchTableInfoFromMahaiak(tableId) }.getOrElse { TableInfo(label = null, guestCount = null) }
                }
                val guestCount =
                    withContext(Dispatchers.IO) {
                        tableInfo.guestCount ?: fetchGuestCountFromErreserba(session.erreserbaId)
                    }
                val categories = withContext(Dispatchers.IO) { fetchCategories() }
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
        resolveClosedFactura(tableId, action = "reopen")
    }

    fun closeFactura(fakturaId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                withContext(Dispatchers.IO) { patchOrdaindu(fakturaId) }
                _uiState.value = _uiState.value.copy(isLoading = false, error = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loadClosePreview(fakturaId: Int) {
        viewModelScope.launch {
            try {
                _uiState.value =
                    _uiState.value.copy(
                        isClosePreviewLoading = true,
                        closePreviewFakturaId = fakturaId,
                        closePreviewLines = emptyList(),
                        error = null
                    )
                val lines =
                    withContext(Dispatchers.IO) {
                        val items = fetchKomandakItemsByFaktura(fakturaId)
                        val totals = items.groupBy { it.platerakId }.mapValues { (_, list) -> list.sumOf { it.kopurua } }
                        val result = ArrayList<ConsumptionLine>(totals.size)
                        for ((platerakId, qty) in totals) {
                            val name = fetchPlateraName(platerakId)
                            result.add(ConsumptionLine(name = name, qty = qty))
                        }
                        result.sortedWith(compareBy({ it.name.lowercase() }, { it.qty }))
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
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val session = withContext(Dispatchers.IO) { ensureSession(tableId, action) }
                val tableInfo = withContext(Dispatchers.IO) {
                    runCatching { fetchTableInfoFromMahaiak(tableId) }.getOrElse { TableInfo(label = null, guestCount = null) }
                }
                val guestCount =
                    withContext(Dispatchers.IO) {
                        tableInfo.guestCount ?: fetchGuestCountFromErreserba(session.erreserbaId)
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
                is JSONObject -> root.optJSONArray("mahaiak") ?: root.optJSONArray("Mahaiak") ?: JSONArray()
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
                    else -> -1
                }
            val guestCount = guestCountRaw.takeIf { it > 0 }

            return TableInfo(label = label, guestCount = guestCount)
        }

        return TableInfo(label = tableId.toString(), guestCount = null)
    }

    private fun ensureSession(tableId: Int, action: String?): TableSession {
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

        val now = java.util.Date()
        val calendar = java.util.Calendar.getInstance().apply { time = now }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val txanda = if (hour in 12..18) "Bazkaria" else "Afaria"
        val data = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(now)

        val erreserbaId =
            fetchErreserbaIdFromMahaiak(tableId)
                ?: run {
                    val erreserbak = fetchGaurkoErreserbak()
                    val erreserba = findErreserbaForTable(erreserbak, tableId)
                    erreserba?.optInt("id", erreserba.optInt("Id", -1))?.takeIf { it > 0 }
                }
                ?: throw IllegalStateException("Ez dago erreserbarik $tableId. mahaiarentzako momentu honetan")

        var faktura = fetchFakturaByErreserba(erreserbaId) ?: fetchFakturaByErreserbaFromList(erreserbaId)
        if (faktura == null) {
            faktura = createFaktura(erreserbaId)
        }

        val fakturaId = faktura.optInt("id", faktura.optInt("Id", -1))
        if (fakturaId <= 0) throw IllegalStateException("Ezin izan da $tableId. mahaiaren faktura lortu")

        val fakturaEgoera = faktura.optBoolean("egoera", faktura.optBoolean("Egoera", false))
        val fakturaPdf = faktura.optString("fakturaPdf", faktura.optString("FakturaPdf", ""))
        val fakturaTotala = fetchFakturaTotala(fakturaId)

        when (action) {
            "reopen" -> {
                if (fakturaEgoera) {
                    putFaktura(
                        id = fakturaId,
                        totala = fakturaTotala,
                        egoera = false,
                        fakturaPdf = fakturaPdf,
                        erreserbakId = erreserbaId
                    )
                }
                return TableSession(
                    mahaiId = tableId,
                    erreserbaMahaiId = 0,
                    erreserbaId = erreserbaId,
                    fakturaId = fakturaId,
                    fakturaEgoera = false,
                    fakturaTotala = fakturaTotala,
                    requiresDecision = false,
                    txanda = txanda,
                    data = data
                )
            }
            "new" -> {
                val newFaktura = createFaktura(erreserbaId)
                val newFakturaId = newFaktura.optInt("id", newFaktura.optInt("Id", -1))
                if (newFakturaId <= 0) throw IllegalStateException("Ezin izan da faktura berria sortu $tableId. mahaiarentzako")
                return TableSession(
                    mahaiId = tableId,
                    erreserbaMahaiId = 0,
                    erreserbaId = erreserbaId,
                    fakturaId = newFakturaId,
                    fakturaEgoera = false,
                    fakturaTotala = 0.0,
                    requiresDecision = false,
                    txanda = txanda,
                    data = data
                )
            }
        }

        return TableSession(
            mahaiId = tableId,
            erreserbaMahaiId = 0,
            erreserbaId = erreserbaId,
            fakturaId = fakturaId,
            fakturaEgoera = fakturaEgoera,
            fakturaTotala = fakturaTotala,
            requiresDecision = fakturaEgoera,
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

    private fun fetchGuestCountFromErreserba(erreserbaId: Int): Int? {
        if (erreserbaId <= 0) return null
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
            return JSONTokener(body).nextValue() as? JSONObject ?: JSONObject(body)
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
                    is JSONObject -> root.optJSONArray("fakturak") ?: root.optJSONArray("Fakturak") ?: root.optJSONArray("data")
                        ?: root.optJSONArray("result") ?: JSONArray()
                    else -> JSONArray()
                }
                var best: JSONObject? = null
                var bestIsOpen = false
                var bestId = -1
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", obj.optInt("Id", -1))
                    if (id <= 0) continue
                    val eId = obj.optInt("erreserbakId", obj.optInt("ErreserbakId", -1))
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
            is JSONObject -> root.optJSONArray("kategoriak") ?: JSONArray()
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
