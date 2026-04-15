package com.example.osislogin.ui

import com.example.osislogin.util.SessionManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

data class Platera(
    val id: Int,
    val kategoriakId: Int,
    val name: String,
    val price: Double,
    val stock: Int
)

data class KomandaItem(
    val id: Int,
    val platerakId: Int,
    val fakturakId: Int,
    val kopurua: Int,
    val oharrak: String?,
    val egoera: Int
)

data class PlaterakUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val tableLabel: String? = null,
    val guestCount: Int? = null,
    val kategoriId: Int = 0,
    val fakturaId: Int = 0,
    val platerak: List<Platera> = emptyList(),
    val komandakByPlateraId: Map<Int, List<KomandaItem>> = emptyMap(),
    val pendingQtyByPlateraId: Map<Int, Int> = emptyMap(),
    val pendingNotesByPlateraId: Map<Int, String?> = emptyMap()
)

class PlaterakViewModel(
    private val sessionManager: SessionManager
) : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://172.16.237.29:5093/api"
    private val apiBaseCandidates = listOf(apiBaseUrlLanPrimary)

    private val _uiState = MutableStateFlow(PlaterakUiState())
    val uiState: StateFlow<PlaterakUiState> = _uiState
    private var autoRefreshJob: Job? = null
    private var currentTableId: Int = 0
    private var currentKomensalak: Int = 1
    private var currentErreserbaId: Int? = null
    private var currentData: String = ""
    private var currentTxanda: String = "Bazkaria"

    private data class TableInfo(val label: String?, val guestCount: Int?)

    private fun readNullableString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key)) continue
            if (obj.isNull(key)) return null
            val value = obj.optString(key, "")
            val cleaned = value.trim()
            if (cleaned.isEmpty()) return null
            if (cleaned.equals("null", ignoreCase = true)) return null
            return cleaned
        }
        return null
    }

    fun load(
        tableId: Int,
        fakturaId: Int,
        kategoriId: Int,
        komensalak: Int,
        erreserbaId: Int?,
        data: String,
        txanda: String
    ) {
        viewModelScope.launch {
            try {
                currentTableId = tableId
                currentKomensalak = komensalak.coerceAtLeast(1)
                currentErreserbaId = erreserbaId
                currentData = data
                currentTxanda = txanda
                val resolvedFakturaId =
                    withContext(Dispatchers.IO) {
                        if (fakturaId > 0) {
                            fakturaId
                        } else {
                            fetchActiveEskaera(tableId, data, txanda)
                                ?.let { fetchFakturaIdByEskaeraId(it.eskaeraId) }
                                ?: 0
                        }
                    }

                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    fakturaId = resolvedFakturaId,
                    kategoriId = kategoriId
                )
                val tableInfo =
                    withContext(Dispatchers.IO) {
                        runCatching { fetchTableInfoFromMahaiak(tableId) }.getOrElse { TableInfo(label = null, guestCount = null) }
                    }
                val platerak = withContext(Dispatchers.IO) { fetchPlaterak(kategoriId) }
                val komandak = withContext(Dispatchers.IO) { fetchKomandak(resolvedFakturaId) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    tableLabel = tableInfo.label,
                    guestCount = komensalak.takeIf { it > 0 } ?: tableInfo.guestCount,
                    platerak = platerak,
                    komandakByPlateraId = komandak.groupBy { it.platerakId },
                    pendingQtyByPlateraId = emptyMap(),
                    pendingNotesByPlateraId = emptyMap()
                )
                if (resolvedFakturaId > 0) {
                    startAutoRefresh(fakturaId = resolvedFakturaId, kategoriId = kategoriId)
                } else {
                    stopAutoRefresh()
                }
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

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun startAutoRefresh(fakturaId: Int, kategoriId: Int) {
        if (fakturaId <= 0) {
            stopAutoRefresh()
            return
        }
        autoRefreshJob?.cancel()
        autoRefreshJob =
            viewModelScope.launch {
                while (isActive) {
                    try {
                        if (_uiState.value.pendingQtyByPlateraId.isNotEmpty() || _uiState.value.pendingNotesByPlateraId.isNotEmpty()) {
                            delay(2000)
                            continue
                        }
                        val (platerak, komandak) =
                            withContext(Dispatchers.IO) {
                                fetchPlaterak(kategoriId) to fetchKomandak(fakturaId)
                            }

                        _uiState.value =
                            _uiState.value.copy(
                                platerak = platerak,
                                komandakByPlateraId = komandak.groupBy { it.platerakId },
                                error = null
                            )
                    } catch (_: Exception) {
                    }
                    delay(2000)
                }
            }
    }

    fun changeQuantity(plateraId: Int, delta: Int) {
        viewModelScope.launch {
            try {
                val komandak = _uiState.value.komandakByPlateraId[plateraId].orEmpty()
                val committedDoneQty = komandak.filter { it.egoera != 0 }.sumOf { it.kopurua }
                val committedTotalQty = komandak.sumOf { it.kopurua }
                val currentDisplayed = _uiState.value.pendingQtyByPlateraId[plateraId] ?: committedTotalQty
                val nextDisplayed = (currentDisplayed + delta).coerceAtLeast(committedDoneQty)
                val pending = _uiState.value.pendingQtyByPlateraId.toMutableMap()
                if (nextDisplayed == committedTotalQty) {
                    pending.remove(plateraId)
                } else {
                    pending[plateraId] = nextDisplayed
                }
                _uiState.value =
                    _uiState.value.copy(
                        pendingQtyByPlateraId = pending,
                        error = null
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun updateNote(plateraId: Int, note: String) {
        viewModelScope.launch {
            try {
                val cleanedNote = note.trim().takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }.orEmpty()
                val pending = _uiState.value.pendingNotesByPlateraId.toMutableMap()
                if (cleanedNote.isBlank()) pending.remove(plateraId) else pending[plateraId] = cleanedNote
                _uiState.value = _uiState.value.copy(pendingNotesByPlateraId = pending, error = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun commitPendingChanges(onDone: () -> Unit) {
        val kategoriId = _uiState.value.kategoriId
        viewModelScope.launch {
            val pendingQty = _uiState.value.pendingQtyByPlateraId
            val pendingNotes = _uiState.value.pendingNotesByPlateraId
            if (pendingQty.isEmpty() && pendingNotes.isEmpty()) {
                onDone()
                return@launch
            }
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                var effectiveFakturaId = _uiState.value.fakturaId
                if (effectiveFakturaId <= 0) {
                    effectiveFakturaId =
                        withContext(Dispatchers.IO) {
                            createInitialEskaeraAndResolveFaktura(
                                pendingQty = pendingQty,
                                pendingNotes = pendingNotes
                            )
                        }
                } else {
                    withContext(Dispatchers.IO) {
                        val touched = (pendingQty.keys + pendingNotes.keys).toSet()
                        for (platerakId in touched) {
                            val komandak = fetchKomandak(effectiveFakturaId).filter { it.platerakId == platerakId }
                            val committedTotal = komandak.sumOf { it.kopurua }
                            val desiredTotal = pendingQty[platerakId] ?: committedTotal
                            applyDesiredTotalQuantity(
                                fakturaId = effectiveFakturaId,
                                platerakId = platerakId,
                                desiredTotal = desiredTotal
                            )

                            val note = pendingNotes[platerakId]?.trim().orEmpty()
                            if (note.isNotBlank()) {
                                val updated = fetchKomandak(effectiveFakturaId).filter { it.platerakId == platerakId }
                                val target = updated.maxByOrNull { it.id }
                                if (target != null) {
                                    putOharrak(komanda = target, oharrak = note)
                                }
                            }
                        }
                    }
                }

                val refreshedPlaterak = withContext(Dispatchers.IO) { fetchPlaterak(kategoriId) }
                val refreshedKomandak = withContext(Dispatchers.IO) { fetchKomandak(effectiveFakturaId) }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        fakturaId = effectiveFakturaId,
                        platerak = refreshedPlaterak,
                        komandakByPlateraId = refreshedKomandak.groupBy { it.platerakId },
                        pendingQtyByPlateraId = emptyMap(),
                        pendingNotesByPlateraId = emptyMap()
                    )
                startAutoRefresh(fakturaId = effectiveFakturaId, kategoriId = kategoriId)
                onDone()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fetchPlaterak(kategoriId: Int): List<Platera> {
        var lastError: String? = null
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Platerak/kategoria/$kategoriId",
                    "$base/platerak/kategoria/$kategoriId",
                    "$base/platerak?kategoriakId=$kategoriId",
                    "$base/Platerak?kategoriakId=$kategoriId"
                )
            }

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
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            okBody = body
            break
        }

        val finalBody = okBody ?: throw IllegalStateException("Ezin izan da /platerak kargatu ($lastError)")

        val root = JSONTokener(finalBody).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject ->
                root.optJSONArray("datuak")
                    ?: root.optJSONArray("Datuak")
                    ?: root.optJSONArray("platerak")
                    ?: JSONArray()
            else -> JSONArray()
        }

        val result = ArrayList<Platera>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            val kategoriakId = obj.optInt("kategoriakId", obj.optInt("KategoriakId", -1))
            val name = obj.optString("izena", obj.optString("Izena", ""))
            val price = obj.optDouble("prezioa", obj.optDouble("Prezioa", 0.0))
            val stock = obj.optInt("stock", obj.optInt("Stock", 0))
            if (id > 0) {
                result.add(Platera(id = id, kategoriakId = kategoriakId, name = name, price = price, stock = stock))
            }
        }

        return result
    }

    private data class ActiveEskaeraInfo(val eskaeraId: Int)

    private fun fetchActiveEskaera(tableId: Int, data: String, txanda: String): ActiveEskaeraInfo? {
        val candidates = apiBaseCandidates.flatMap { base ->
            listOf(
                "$base/eskaerak/mahaia/$tableId/aktiboa?data=$data&txanda=$txanda",
                "$base/Eskaerak/mahaia/$tableId/aktiboa?data=$data&txanda=$txanda"
            )
        }

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
                if (eskaeraId > 0) return ActiveEskaeraInfo(eskaeraId)
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun fetchFakturaIdByEskaeraId(eskaeraId: Int): Int? {
        val candidates = apiBaseCandidates.flatMap { base ->
            listOf(
                "$base/fakturak",
                "$base/Fakturak"
            )
        }

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
                var bestId: Int? = null
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val currentEskaeraId = obj.optInt("eskaeraId", obj.optInt("EskaeraId", -1))
                    if (currentEskaeraId != eskaeraId) continue
                    val currentId = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
                    if (bestId == null || currentId > bestId) bestId = currentId
                }
                if (bestId != null) return bestId
            } catch (_: Exception) {
            }
        }

        return null
    }

    private suspend fun createInitialEskaeraAndResolveFaktura(
        pendingQty: Map<Int, Int>,
        pendingNotes: Map<Int, String?>
    ): Int {
        val produktuak = JSONArray()
        val currentPlaterak = _uiState.value.platerak.associateBy { it.id }

        for ((platerakId, qty) in pendingQty) {
            if (qty <= 0) continue
            val platera = currentPlaterak[platerakId]
                ?: throw IllegalStateException("Ez da platera aurkitu: $platerakId")
            produktuak.put(
                JSONObject()
                    .put("produktuaId", platerakId)
                    .put("kantitatea", qty)
                    .put("prezioUnitarioa", platera.price)
            )
        }

        if (produktuak.length() == 0) {
            throw IllegalStateException("Ez dago produkturik eskaera sortzeko")
        }

        val erabiltzaileId = sessionManager.userId.first()
            ?: throw IllegalStateException("Saioan erabiltzaileId falta da. Egin login berriro.")

        val payload = JSONObject()
            .put("erabiltzaileId", erabiltzaileId)
            .put("mahaiaId", currentTableId)
            .put("komensalak", currentKomensalak.coerceAtLeast(1))
            .put("erreserbaId", currentErreserbaId)
            .put("data", currentData)
            .put("txanda", currentTxanda)
            .put("produktuak", produktuak)
            .toString()

        var lastError: String? = null
        var eskaeraId: Int? = null
        for (candidateUrl in apiBaseCandidates.flatMap { base -> listOf("$base/eskaerak", "$base/Eskaerak") }) {
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
                eskaeraId = array.optInt(0, -1).takeIf { it > 0 }
                if (eskaeraId != null) break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val finalEskaeraId = eskaeraId ?: throw IllegalStateException("Ezin izan da eskaera sortu ($lastError)")
        val fakturaId = fetchFakturaIdByEskaeraId(finalEskaeraId)
            ?: throw IllegalStateException("Ezin izan da faktura lortu eskaera sortu ondoren")

        for ((platerakId, noteRaw) in pendingNotes) {
            val note = noteRaw?.trim().orEmpty()
            if (note.isBlank()) continue
            val updated = fetchKomandak(fakturaId).filter { it.platerakId == platerakId }
            val target = updated.maxByOrNull { it.id } ?: continue
            putOharrak(target, note)
        }

        return fakturaId
    }

    private fun fetchKomandak(fakturaId: Int): List<KomandaItem> {
        if (fakturaId <= 0) return emptyList()
        var lastError: String? = null
        var sawNotFound = false
        var sawOtherError = false
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Komandak/faktura/$fakturaId",
                    "$base/komandak/faktura/$fakturaId",
                    "$base/komandak/faktura/$fakturaId/items",
                    "$base/Komandak/faktura/$fakturaId/items"
                )
            }

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
            val body = stream.bufferedReader().use { it.readText() }
            if (code == 404) {
                sawNotFound = true
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            if (code !in 200..299) {
                sawOtherError = true
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                continue
            }
            okBody = body
            break
        }

        val finalBody = okBody ?: run {
            if (sawNotFound && !sawOtherError) return emptyList()
            throw IllegalStateException("Ezin izan da /komandak/faktura kargatu ($lastError)")
        }

        val root = JSONTokener(finalBody).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject ->
                root.optJSONArray("datuak")
                    ?: root.optJSONArray("Datuak")
                    ?: root.optJSONArray("komandak")
                    ?: JSONArray()
            else -> JSONArray()
        }

        val result = ArrayList<KomandaItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            val platerakId =
                obj.optInt("platerakId", obj.optInt("PlaterakId", -1)).takeIf { it > 0 }
                    ?: run {
                        val pObj = obj.optJSONObject("platerak") ?: obj.optJSONObject("Platerak")
                        pObj?.optInt("id", pObj.optInt("Id", -1))
                    }
                    ?: -1
            val fakturakId =
                obj.optInt("fakturakId", obj.optInt("FakturakId", -1)).takeIf { it > 0 }
                    ?: run {
                        val fObj = obj.optJSONObject("faktura") ?: obj.optJSONObject("Faktura")
                        fObj?.optInt("id", fObj.optInt("Id", -1))
                    }
                    ?: -1
            val kopurua = obj.optInt("kopurua", obj.optInt("Kopurua", 0))
            val oharrak = readNullableString(obj, "oharrak", "Oharrak")
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
            if (id > 0 && platerakId > 0) {
                result.add(
                    KomandaItem(
                        id = id,
                        platerakId = platerakId,
                        fakturakId = fakturakId,
                        kopurua = kopurua,
                        oharrak = oharrak,
                        egoera = egoera
                    )
                )
            }
        }

        return result
    }

    private fun applyDesiredTotalQuantity(fakturaId: Int, platerakId: Int, desiredTotal: Int) {
        val all = fetchKomandak(fakturaId).filter { it.platerakId == platerakId }
        val doneQty = all.filter { it.egoera != 0 }.sumOf { it.kopurua }
        val open = all.filter { it.egoera == 0 }
        val committedTotal = all.sumOf { it.kopurua }
        val targetTotal = desiredTotal.coerceAtLeast(doneQty)
        val diff = targetTotal - committedTotal
        if (diff == 0) return

        if (diff > 0) {
            val latest = all.maxByOrNull { it.id }
            val latestOpen = latest?.takeIf { it.egoera == 0 }
            if (latestOpen != null) {
                updateKomandaQuantity(komandaId = latestOpen.id, kopurua = latestOpen.kopurua + diff)
            } else {
                postKomanda(fakturaId = fakturaId, platerakId = platerakId, kopurua = diff)
            }
            return
        }

        var remaining = -diff
        for (target in open.sortedByDescending { it.id }) {
            if (remaining <= 0) break
            val newQty = target.kopurua - remaining
            if (newQty > 0) {
                updateKomandaQuantity(komandaId = target.id, kopurua = newQty)
                remaining = 0
            } else {
                deleteKomanda(target.id)
                remaining -= target.kopurua
            }
        }
    }

    private fun deleteKomanda(komandaId: Int) {
        var lastError: String? = null
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Komandak/$komandaId",
                    "$base/komandak/$komandaId"
                )
            }

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

    private fun postKomanda(fakturaId: Int, platerakId: Int, kopurua: Int) {
        var lastError: String? = null
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Komandak",
                    "$base/komandak"
                )
            }

        val payload =
            JSONObject()
                .put("fakturakId", fakturaId)
                .put("platerakId", platerakId)
                .put("kopurua", kopurua)
                .toString()

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
            if (code in 200..299) return
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }

        throw IllegalStateException("Ezin izan da komanda sortu ($lastError)")
    }

    private fun updateKomandaQuantity(komandaId: Int, kopurua: Int) {
        var lastError: String? = null
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/komandak/$komandaId/quantity",
                    "$base/Komandak/$komandaId/quantity"
                )
            }

        val payload = JSONObject().put("kopurua", kopurua).toString()

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

        throw IllegalStateException("Ezin izan da kantitatea eguneratu ($lastError)")
    }

    private fun fetchPlateraStock(platerakId: Int): Int? {
        val candidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/Platerak/$platerakId",
                    "$base/platerak/$platerakId"
                )
            }

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
            val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()
            val obj = parsed as? JSONObject ?: return null
            return obj.optInt("stock", obj.optInt("Stock", Int.MIN_VALUE)).takeIf { it != Int.MIN_VALUE }
        }
        return null
    }

    private fun putOharrak(komanda: KomandaItem, oharrak: String) {
        var lastError: String? = null
        val legacyCandidates =
            apiBaseCandidates.flatMap { base ->
                listOf(
                    "$base/komandak/${komanda.id}/oharrak",
                    "$base/Komandak/${komanda.id}/oharrak"
                )
            }

        val legacyPayload =
            JSONObject()
                .put("oharrak", oharrak.trim().takeUnless { it.isEmpty() } ?: JSONObject.NULL)
                .toString()
        for (candidateUrl in legacyCandidates) {
            val url = URL(candidateUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            conn.outputStream.use { it.write(legacyPayload.toByteArray()) }

            val code = conn.responseCode
            if (code in 200..299) return
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
        }

        throw IllegalStateException("Ezin izan da oharra gorde ($lastError)")
    }
}
