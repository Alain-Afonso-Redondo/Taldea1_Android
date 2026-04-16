package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.util.SessionManager
import com.example.osislogin.util.ZerbitzariakApiConfig
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
    val allCommittedQtyByPlateraId: Map<Int, Int> = emptyMap(),
    val pendingQtyByPlateraId: Map<Int, Int> = emptyMap(),
    val pendingNotesByPlateraId: Map<Int, String?> = emptyMap()
)

class PlaterakViewModel(
    private val sessionManager: SessionManager
) : ViewModel() {
    private val apiBaseUrlLanPrimary = ZerbitzariakApiConfig.primaryBaseUrl
    private val apiBaseCandidates = ZerbitzariakApiConfig.baseUrlCandidates()

    private val _uiState = MutableStateFlow(PlaterakUiState())
    val uiState: StateFlow<PlaterakUiState> = _uiState

    private var autoRefreshJob: Job? = null
    private var currentTableId: Int = 0
    private var currentKomensalak: Int = 1
    private var currentErreserbaId: Int? = null
    private var currentData: String = ""
    private var currentTxanda: String = "Bazkaria"
    private var currentEskaeraId: Int = 0

    private data class TableInfo(val label: String?, val guestCount: Int?)

    private data class ActiveEskaeraInfo(
        val eskaeraId: Int,
        val komensalak: Int?,
        val erreserbaId: Int?
    )

    private data class SaveResult(val eskaeraId: Int, val fakturaId: Int)

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

                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    kategoriId = kategoriId,
                    fakturaId = fakturaId
                )

                val tableInfo = withContext(Dispatchers.IO) {
                    runCatching { fetchTableInfoFromMahaiak(tableId) }.getOrElse { TableInfo(null, null) }
                }
                val activeEskaera = withContext(Dispatchers.IO) { fetchActiveEskaera(tableId, data, txanda) }
                currentEskaeraId = activeEskaera?.eskaeraId ?: 0
                if (activeEskaera?.komensalak != null && activeEskaera.komensalak > 0) {
                    currentKomensalak = activeEskaera.komensalak
                }
                currentErreserbaId = activeEskaera?.erreserbaId ?: erreserbaId

                val resolvedFakturaId = withContext(Dispatchers.IO) {
                    when {
                        fakturaId > 0 -> fakturaId
                        currentEskaeraId > 0 -> fetchFakturaIdByEskaeraId(currentEskaeraId) ?: 0
                        else -> 0
                    }
                }
                val platerak = withContext(Dispatchers.IO) { fetchPlaterak(kategoriId) }
                val committedQtyByPlateraId = withContext(Dispatchers.IO) {
                    if (currentEskaeraId > 0) fetchEskaeraProductQuantities(currentEskaeraId) else emptyMap()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    tableLabel = tableInfo.label,
                    guestCount = currentKomensalak.takeIf { it > 0 } ?: tableInfo.guestCount,
                    fakturaId = resolvedFakturaId,
                    platerak = platerak,
                    komandakByPlateraId = committedQtyByPlateraId.toKomandaMap(resolvedFakturaId),
                    allCommittedQtyByPlateraId = committedQtyByPlateraId,
                    pendingQtyByPlateraId = emptyMap(),
                    pendingNotesByPlateraId = emptyMap(),
                    error = null
                )

                if (currentEskaeraId > 0) startAutoRefresh(kategoriId) else stopAutoRefresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun startAutoRefresh(kategoriId: Int) {
        autoRefreshJob?.cancel()
        autoRefreshJob =
            viewModelScope.launch {
                while (isActive) {
                    try {
                        if (_uiState.value.pendingQtyByPlateraId.isNotEmpty()) {
                            delay(2000)
                            continue
                        }

                        val activeEskaera = withContext(Dispatchers.IO) {
                            fetchActiveEskaera(currentTableId, currentData, currentTxanda)
                        }
                        currentEskaeraId = activeEskaera?.eskaeraId ?: 0

                        val platerak = withContext(Dispatchers.IO) { fetchPlaterak(kategoriId) }
                        val committedQtyByPlateraId = withContext(Dispatchers.IO) {
                            if (currentEskaeraId > 0) fetchEskaeraProductQuantities(currentEskaeraId) else emptyMap()
                        }
                        val fakturaId = withContext(Dispatchers.IO) {
                            if (currentEskaeraId > 0) fetchFakturaIdByEskaeraId(currentEskaeraId) ?: 0 else 0
                        }

                        _uiState.value = _uiState.value.copy(
                            platerak = platerak,
                            fakturaId = fakturaId,
                            komandakByPlateraId = committedQtyByPlateraId.toKomandaMap(fakturaId),
                            allCommittedQtyByPlateraId = committedQtyByPlateraId,
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
                val committedTotalQty = _uiState.value.allCommittedQtyByPlateraId[plateraId] ?: 0
                val currentDisplayed = _uiState.value.pendingQtyByPlateraId[plateraId] ?: committedTotalQty
                val nextDisplayed = (currentDisplayed + delta).coerceAtLeast(0)
                val pending = _uiState.value.pendingQtyByPlateraId.toMutableMap()
                if (nextDisplayed == committedTotalQty) pending.remove(plateraId) else pending[plateraId] = nextDisplayed
                _uiState.value = _uiState.value.copy(pendingQtyByPlateraId = pending, error = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun updateNote(plateraId: Int, note: String) {
        val cleaned = note.trim()
        val pending = _uiState.value.pendingNotesByPlateraId.toMutableMap()
        if (cleaned.isBlank()) pending.remove(plateraId) else pending[plateraId] = cleaned
        _uiState.value = _uiState.value.copy(
            pendingNotesByPlateraId = pending,
            error = "Oharrak oraindik ez daude lotuta API berriarekin."
        )
    }

    fun commitPendingChanges(onDone: () -> Unit) {
        viewModelScope.launch {
            val pendingQty = _uiState.value.pendingQtyByPlateraId
            val hadPendingNotes = _uiState.value.pendingNotesByPlateraId.isNotEmpty()
            if (pendingQty.isEmpty()) {
                onDone()
                return@launch
            }

            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val result = withContext(Dispatchers.IO) { saveCurrentCategoryChanges() }
                currentEskaeraId = result.eskaeraId

                val refreshedPlaterak = withContext(Dispatchers.IO) { fetchPlaterak(_uiState.value.kategoriId) }
                val refreshedQtyByPlateraId = withContext(Dispatchers.IO) {
                    if (currentEskaeraId > 0) fetchEskaeraProductQuantities(currentEskaeraId) else emptyMap()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    fakturaId = result.fakturaId,
                    platerak = refreshedPlaterak,
                    komandakByPlateraId = refreshedQtyByPlateraId.toKomandaMap(result.fakturaId),
                    allCommittedQtyByPlateraId = refreshedQtyByPlateraId,
                    pendingQtyByPlateraId = emptyMap(),
                    pendingNotesByPlateraId = emptyMap(),
                    error = if (hadPendingNotes) {
                        "Kantitateak gorde dira. Oharrak ez daude erabilgarri API berrian."
                    } else {
                        null
                    }
                )

                if (currentEskaeraId > 0) startAutoRefresh(_uiState.value.kategoriId) else stopAutoRefresh()
                onDone()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    private suspend fun saveCurrentCategoryChanges(): SaveResult {
        val ui = _uiState.value
        val mergedTotals = ui.allCommittedQtyByPlateraId.toMutableMap()

        for (platera in ui.platerak) {
            val desiredQty = ui.pendingQtyByPlateraId[platera.id] ?: (ui.allCommittedQtyByPlateraId[platera.id] ?: 0)
            if (desiredQty > 0) mergedTotals[platera.id] = desiredQty else mergedTotals.remove(platera.id)
        }

        val positiveTotals = mergedTotals.filterValues { it > 0 }
        if (currentEskaeraId <= 0 && positiveTotals.isEmpty()) return SaveResult(0, 0)

        if (currentEskaeraId > 0 && positiveTotals.isEmpty()) {
            deleteEskaera(currentEskaeraId)
            return SaveResult(0, 0)
        }

        val effectiveEskaeraId =
            if (currentEskaeraId > 0) {
                updateEskaera(currentEskaeraId, positiveTotals)
                currentEskaeraId
            } else {
                createEskaera(positiveTotals)
            }

        return SaveResult(
            eskaeraId = effectiveEskaeraId,
            fakturaId = fetchFakturaIdByEskaeraId(effectiveEskaeraId) ?: 0
        )
    }

    private fun fetchPlaterak(kategoriId: Int): List<Platera> {
        val array = getWrappedArray(
            candidates = apiBaseCandidates.flatMap { base -> listOf("$base/produktuak", "$base/Produktuak") },
            errorMessage = "Ezin izan dira produktuak kargatu"
        )

        val result = ArrayList<Platera>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1))
            val kategoriakId =
                obj.optInt(
                    "kategoria_id",
                    obj.optInt("KategoriaId", obj.optInt("kategoriakId", obj.optInt("KategoriakId", -1)))
                )
            val name = obj.optString("izena", obj.optString("Izena", "")).trim()
            val price = obj.optDouble("prezioa", obj.optDouble("Prezioa", 0.0))
            val stock = obj.optInt("stock_aktuala", obj.optInt("StockAktuala", obj.optInt("stock", obj.optInt("Stock", 0))))
            if (id > 0 && kategoriakId == kategoriId && name.isNotBlank()) {
                result.add(Platera(id = id, kategoriakId = kategoriakId, name = name, price = price, stock = stock))
            }
        }

        return result.sortedBy { it.name.lowercase() }
    }

    private fun fetchEskaeraProductQuantities(eskaeraId: Int): Map<Int, Int> {
        val array = getWrappedArray(
            candidates = apiBaseCandidates.flatMap { base -> listOf("$base/eskaerak/$eskaeraId/produktuak", "$base/Eskaerak/$eskaeraId/produktuak") },
            errorMessage = "Ezin izan dira eskaerako produktuak kargatu"
        )

        val totals = linkedMapOf<Int, Int>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val produktuaId = obj.optInt("ProduktuaId", obj.optInt("produktuaId", -1))
            val kantitatea = obj.optInt("Kantitatea", obj.optInt("kantitatea", 0))
            if (produktuaId > 0 && kantitatea > 0) {
                totals[produktuaId] = (totals[produktuaId] ?: 0) + kantitatea
            }
        }
        return totals
    }

    private suspend fun createEskaera(productTotals: Map<Int, Int>): Int {
        val produktuak = JSONArray()
        val currentPlaterak = _uiState.value.platerak.associateBy { it.id }

        for ((produktuaId, qty) in productTotals) {
            val platera = currentPlaterak[produktuaId]
            produktuak.put(
                JSONObject()
                    .put("produktuaId", produktuaId)
                    .put("kantitatea", qty)
                    .put("prezioUnitarioa", platera?.price ?: 0.0)
            )
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
        for (candidateUrl in apiBaseCandidates.flatMap { base -> listOf("$base/eskaerak", "$base/Eskaerak") }) {
            try {
                val body = executeRequest(candidateUrl, "POST", payload).orEmpty()
                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                val array = when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("datuak") ?: root.optJSONArray("Datuak") ?: JSONArray()
                    else -> JSONArray()
                }
                val eskaeraId = array.optInt(0, -1)
                if (eskaeraId > 0) return eskaeraId
                lastError = "url=$candidateUrl body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da eskaera sortu ($lastError)")
    }

    private fun updateEskaera(eskaeraId: Int, productTotals: Map<Int, Int>) {
        val produktuak = JSONArray()
        for ((produktuaId, qty) in productTotals) {
            produktuak.put(
                JSONObject()
                    .put("produktuaId", produktuaId)
                    .put("kantitatea", qty)
            )
        }

        val payload = JSONObject()
            .put("komensalak", currentKomensalak.coerceAtLeast(1))
            .put("produktuak", produktuak)
            .toString()

        var lastError: String? = null
        for (candidateUrl in apiBaseCandidates.flatMap { base -> listOf("$base/eskaerak/$eskaeraId", "$base/Eskaerak/$eskaeraId") }) {
            try {
                executeRequest(candidateUrl, "PUT", payload)
                return
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da eskaera eguneratu ($lastError)")
    }

    private fun deleteEskaera(eskaeraId: Int) {
        var lastError: String? = null
        for (candidateUrl in apiBaseCandidates.flatMap { base -> listOf("$base/eskaerak/$eskaeraId", "$base/Eskaerak/$eskaeraId") }) {
            try {
                executeRequest(candidateUrl, "DELETE")
                return
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan da eskaera ezabatu ($lastError)")
    }

    private fun fetchActiveEskaera(tableId: Int, data: String, txanda: String): ActiveEskaeraInfo? {
        for (candidateUrl in apiBaseCandidates.flatMap { base ->
            listOf(
                "$base/eskaerak/mahaia/$tableId/aktiboa?data=$data&txanda=$txanda",
                "$base/Eskaerak/mahaia/$tableId/aktiboa?data=$data&txanda=$txanda"
            )
        }) {
            try {
                val body = executeRequest(candidateUrl, "GET", allowNotFound = true) ?: continue
                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                val array = when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("datuak") ?: root.optJSONArray("Datuak") ?: JSONArray()
                    else -> JSONArray()
                }
                val obj = array.optJSONObject(0) ?: continue
                val eskaeraId = obj.optInt("Id", obj.optInt("id", -1))
                if (eskaeraId > 0) {
                    return ActiveEskaeraInfo(
                        eskaeraId = eskaeraId,
                        komensalak = obj.optInt("Komensalak", obj.optInt("komensalak", -1)).takeIf { it > 0 },
                        erreserbaId = obj.optInt("ErreserbaId", obj.optInt("erreserbaId", -1)).takeIf { it > 0 }
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchFakturaIdByEskaeraId(eskaeraId: Int): Int? {
        for (candidateUrl in apiBaseCandidates.flatMap { base -> listOf("$base/fakturak", "$base/Fakturak") }) {
            try {
                val body = executeRequest(candidateUrl, "GET")
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
                okBody = executeRequest(candidateUrl, "GET")
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
                                        obj.optString("Mahaia", obj.optString("id", obj.optString("Id", tableId.toString())))
                                    )
                                )
                            )
                        )
                    )
                    .trim()
                    .ifBlank { tableId.toString() }

            val guestCount =
                when {
                    obj.has("pertsonaKopurua") -> obj.optInt("pertsonaKopurua", -1)
                    obj.has("pertsona_kopurua") -> obj.optInt("pertsona_kopurua", -1)
                    obj.has("PertsonaKopurua") -> obj.optInt("PertsonaKopurua", -1)
                    obj.has("kapazitatea") -> obj.optInt("kapazitatea", -1)
                    obj.has("Kapazitatea") -> obj.optInt("Kapazitatea", -1)
                    else -> -1
                }.takeIf { it > 0 }

            return TableInfo(label = label, guestCount = guestCount)
        }

        return TableInfo(label = tableId.toString(), guestCount = null)
    }

    private fun getWrappedArray(candidates: List<String>, errorMessage: String): JSONArray {
        var lastError: String? = null
        for (candidateUrl in candidates.distinct()) {
            try {
                val body = executeRequest(candidateUrl, "GET")
                val root = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                return when (root) {
                    is JSONArray -> root
                    is JSONObject ->
                        root.optJSONArray("datuak")
                            ?: root.optJSONArray("Datuak")
                            ?: root.optJSONArray("data")
                            ?: root.optJSONArray("result")
                            ?: JSONArray()
                    else -> JSONArray()
                }
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("$errorMessage ($lastError)")
    }

    private fun executeRequest(
        urlString: String,
        method: String,
        payload: String? = null,
        allowNotFound: Boolean = false
    ): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 15000
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        if (payload != null) {
            conn.outputStream.use { it.write(payload.toByteArray()) }
        }

        val code = conn.responseCode
        if (allowNotFound && code == 404) return null

        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("code=$code body=${body.take(200)}")
        }
        return body
    }

    private fun Map<Int, Int>.toKomandaMap(fakturaId: Int): Map<Int, List<KomandaItem>> {
        if (isEmpty()) return emptyMap()
        return entries.associate { (produktuaId, qty) ->
            produktuaId to listOf(
                KomandaItem(
                    id = produktuaId,
                    platerakId = produktuaId,
                    fakturakId = fakturaId,
                    kopurua = qty,
                    oharrak = null,
                    egoera = 0
                )
            )
        }
    }
}
