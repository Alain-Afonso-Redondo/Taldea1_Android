package com.example.osiskitchen.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class KitchenPlatosStockUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val platos: List<KitchenPlatoStock> = emptyList(),
    val updatingIds: Set<Int> = emptySet()
)

data class KitchenPlatoStock(
    val id: Int,
    val izena: String,
    val kategoriaId: Int?,
    val kategoriaIzena: String?,
    val stock: Int
)

class KitchenPlatosStockViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.2.101:5000/api"

    private val _uiState = MutableStateFlow(KitchenPlatosStockUiState())
    val uiState: StateFlow<KitchenPlatosStockUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val platos = withContext(Dispatchers.IO) { fetchPlatos() }
                _uiState.value = _uiState.value.copy(isLoading = false, platos = platos)
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun adjustStock(platoId: Int, delta: Int) {
        if (delta == 0) return
        val current = _uiState.value
        if (current.updatingIds.contains(platoId)) return

        val existing = current.platos.firstOrNull { it.id == platoId } ?: return
        val newStock = existing.stock + delta
        if (newStock < 0) return

        val optimistic =
            current.platos.map { if (it.id == platoId) it.copy(stock = newStock) else it }
        _uiState.value =
            current.copy(
                platos = optimistic,
                updatingIds = current.updatingIds + platoId,
                error = null
            )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { applyPlatoStockAndIngredients(platoId, delta) }
                val after = _uiState.value
                _uiState.value = after.copy(updatingIds = after.updatingIds - platoId)
            } catch (e: Exception) {
                val after = _uiState.value
                val reverted =
                    after.platos.map { if (it.id == platoId) it.copy(stock = existing.stock) else it }
                _uiState.value =
                    after.copy(
                        platos = reverted,
                        updatingIds = after.updatingIds - platoId,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    private data class PlateraOsagaiaLite(val osagaiaId: Int, val kopurua: Int)

    private fun applyPlatoStockAndIngredients(platoId: Int, delta: Int) {
        val relations = fetchPlateraOsagaiak(platoId)
        val applied = ArrayList<Pair<Int, Int>>(relations.size)
        try {
            for (relation in relations) {
                val ingredientDelta = -(delta * relation.kopurua)
                if (ingredientDelta == 0) continue
                patchOsagaiaStock(relation.osagaiaId, ingredientDelta)
                applied.add(relation.osagaiaId to ingredientDelta)
            }
            patchPlatoStock(platoId, delta)
        } catch (e: Exception) {
            for ((osagaiaId, ingredientDelta) in applied.asReversed()) {
                runCatching { patchOsagaiaStock(osagaiaId, -ingredientDelta) }
            }
            throw e
        }
    }

    private fun fetchPlateraOsagaiak(platoId: Int): List<PlateraOsagaiaLite> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Platerak/$platoId/osagaiak",
                    "$baseUrl/platerak/$platoId/osagaiak"
                )
            }.distinct()

        var lastError: String? = null
        var body: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                body = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val finalBody = body ?: throw IllegalStateException("Ezin izan dira plateraren osagaiak kargatu ($lastError)")
        val root = JSONTokener(finalBody).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject ->
                    root.optJSONArray("osagaiak")
                        ?: root.optJSONArray("Osagaiak")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONArray("result")
                        ?: root.optJSONArray("\$values")
                        ?: JSONArray()
                else -> JSONArray()
            }

        val result = ArrayList<PlateraOsagaiaLite>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val osagaiaId =
                obj.optInt(
                    "osagaiakId",
                    obj.optInt("OsagaiakId", obj.optInt("osagaiaId", obj.optInt("OsagaiaId", -1)))
                ).takeIf { it > 0 } ?: continue
            val kopurua = obj.optInt("kopurua", obj.optInt("Kopurua", 0))
            if (kopurua == 0) continue
            result.add(PlateraOsagaiaLite(osagaiaId = osagaiaId, kopurua = kopurua))
        }
        return result
    }

    private fun patchOsagaiaStock(osagaiaId: Int, delta: Int) {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Osagaiak/$osagaiaId/stock",
                    "$baseUrl/osagaiak/$osagaiaId/stock"
                )
            }.distinct()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 15000
                    }

                val jsonBody = "{\"kopurua\":$delta}"
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code in 200..299) return
                val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da osagaiaren stock-a eguneratu ($lastError)")
    }

    private fun fetchPlatos(): List<KitchenPlatoStock> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Platerak",
                    "$baseUrl/platerak"
                )
            }.distinct()

        var lastError: String? = null
        var body: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                body = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val finalBody = body ?: throw IllegalStateException("Ezin izan dira platerak kargatu ($lastError)")
        val root = JSONTokener(finalBody).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject ->
                    root.optJSONArray("platerak")
                        ?: root.optJSONArray("Platerak")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONArray("result")
                        ?: JSONArray()
                else -> JSONArray()
            }

        val result = ArrayList<KitchenPlatoStock>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val izena = obj.optString("izena", obj.optString("Izena", "")).trim().ifBlank { "Platera $id" }
            val stock = obj.optInt("stock", obj.optInt("Stock", 0))
            val kategoriaId =
                obj.optInt(
                    "kategoriaId",
                    obj.optInt(
                        "KategoriaId",
                        obj.optInt(
                            "kategoriakId",
                            obj.optInt("KategoriakId", obj.optInt("kategoriak_id", obj.optInt("Kategoriak_Id", -1)))
                        )
                    )
                ).takeIf { it > 0 }
                    ?: run {
                        val kategoriaObj =
                            obj.optJSONObject("kategoria")
                                ?: obj.optJSONObject("Kategoria")
                                ?: obj.optJSONObject("kategoriak")
                                ?: obj.optJSONObject("Kategoriak")
                        kategoriaObj?.optInt("id", kategoriaObj.optInt("Id", -1))?.takeIf { it > 0 }
                    }
            val kategoriaIzena =
                obj.optString("kategoriaIzena", obj.optString("KategoriaIzena", "")).trim().ifBlank { null }
            result.add(
                KitchenPlatoStock(
                    id = id,
                    izena = izena,
                    kategoriaId = kategoriaId,
                    kategoriaIzena = kategoriaIzena,
                    stock = stock
                )
            )
        }
        return result.sortedWith(compareBy<KitchenPlatoStock> { it.kategoriaId ?: Int.MAX_VALUE }.thenBy { it.izena })
    }

    private fun patchPlatoStock(platoId: Int, delta: Int) {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Platerak/$platoId/stock",
                    "$baseUrl/platerak/$platoId/stock"
                )
            }.distinct()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 15000
                    }

                val jsonBody = "{\"kopurua\":$delta}"
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code in 200..299) return
                val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da stock-a eguneratu ($lastError)")
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to body
    }

    private fun apiBaseUrlCandidates(): List<String> {
        val base = apiBaseUrlLanPrimary.trimEnd('/')
        val noApi =
            if (base.endsWith("/api")) {
                base.removeSuffix("/api").trimEnd('/')
            } else {
                base
            }
        return listOf(base, "$noApi/api").distinct()
    }
}

data class KitchenIngredientesStockUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val ingredientes: List<KitchenIngredienteStock> = emptyList(),
    val updatingIds: Set<Int> = emptySet()
)

data class KitchenIngredienteStock(
    val id: Int,
    val izena: String,
    val stock: Int,
    val gutxienekoStock: Int,
    val eskatu: Boolean
)

class KitchenIngredientesStockViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.2.101:5000/api"

    private val _uiState = MutableStateFlow(KitchenIngredientesStockUiState())
    val uiState: StateFlow<KitchenIngredientesStockUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val items = withContext(Dispatchers.IO) { fetchIngredientes() }
                _uiState.value = _uiState.value.copy(isLoading = false, ingredientes = items)
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun adjustStock(ingredienteId: Int, delta: Int) {
        if (delta == 0) return
        val current = _uiState.value
        if (current.updatingIds.contains(ingredienteId)) return

        val existing = current.ingredientes.firstOrNull { it.id == ingredienteId } ?: return
        val newStock = existing.stock + delta
        if (newStock < 0) return

        val optimistic =
            current.ingredientes.map { if (it.id == ingredienteId) it.copy(stock = newStock) else it }
        _uiState.value =
            current.copy(
                ingredientes = optimistic,
                updatingIds = current.updatingIds + ingredienteId,
                error = null
            )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { patchIngredienteStock(ingredienteId, delta) }
                val after = _uiState.value
                _uiState.value = after.copy(updatingIds = after.updatingIds - ingredienteId)
            } catch (e: Exception) {
                val after = _uiState.value
                val reverted =
                    after.ingredientes.map {
                        if (it.id == ingredienteId) it.copy(stock = existing.stock) else it
                    }
                _uiState.value =
                    after.copy(
                        ingredientes = reverted,
                        updatingIds = after.updatingIds - ingredienteId,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    fun toggleEskatu(ingredienteId: Int) {
        val current = _uiState.value
        if (current.updatingIds.contains(ingredienteId)) return

        val existing = current.ingredientes.firstOrNull { it.id == ingredienteId } ?: return
        val optimistic =
            current.ingredientes.map {
                if (it.id == ingredienteId) it.copy(eskatu = !it.eskatu) else it
            }
        _uiState.value =
            current.copy(
                ingredientes = optimistic,
                updatingIds = current.updatingIds + ingredienteId,
                error = null
            )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { patchIngredienteEskatu(ingredienteId) }
                val after = _uiState.value
                _uiState.value = after.copy(updatingIds = after.updatingIds - ingredienteId)
            } catch (e: Exception) {
                val after = _uiState.value
                val reverted =
                    after.ingredientes.map {
                        if (it.id == ingredienteId) it.copy(eskatu = existing.eskatu) else it
                    }
                _uiState.value =
                    after.copy(
                        ingredientes = reverted,
                        updatingIds = after.updatingIds - ingredienteId,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    private fun fetchIngredientes(): List<KitchenIngredienteStock> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Osagaiak",
                    "$baseUrl/osagaiak"
                )
            }.distinct()

        var lastError: String? = null
        var body: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                body = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val finalBody = body ?: throw IllegalStateException("Ezin izan dira osagaiak kargatu ($lastError)")
        val root = JSONTokener(finalBody).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject ->
                    root.optJSONArray("osagaiak")
                        ?: root.optJSONArray("Osagaiak")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONArray("result")
                        ?: JSONArray()
                else -> JSONArray()
            }

        val result = ArrayList<KitchenIngredienteStock>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val izena = obj.optString("izena", obj.optString("Izena", "")).trim().ifBlank { "Osagaia $id" }
            val stock = obj.optInt("stock", obj.optInt("Stock", 0))
            val gutxienekoStock = obj.optInt("gutxienekoStock", obj.optInt("GutxienekoStock", 0))
            val eskatu =
                when {
                    obj.has("eskatu") -> obj.optBoolean("eskatu", false)
                    obj.has("Eskatu") -> obj.optBoolean("Eskatu", false)
                    else -> false
                }
            result.add(
                KitchenIngredienteStock(
                    id = id,
                    izena = izena,
                    stock = stock,
                    gutxienekoStock = gutxienekoStock,
                    eskatu = eskatu
                )
            )
        }
        return result.sortedWith(compareBy<KitchenIngredienteStock> { it.eskatu.not() }.thenBy { it.stock > it.gutxienekoStock }.thenBy { it.izena })
    }

    private fun patchIngredienteStock(ingredienteId: Int, delta: Int) {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Osagaiak/$ingredienteId/stock",
                    "$baseUrl/osagaiak/$ingredienteId/stock"
                )
            }.distinct()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 15000
                    }

                val jsonBody = "{\"kopurua\":$delta}"
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code in 200..299) return
                val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da stock-a eguneratu ($lastError)")
    }

    private fun patchIngredienteEskatu(ingredienteId: Int) {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Osagaiak/$ingredienteId/eskatu",
                    "$baseUrl/osagaiak/$ingredienteId/eskatu"
                )
            }.distinct()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                val code = conn.responseCode
                if (code in 200..299) return
                val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da 'eskatu' eguneratu ($lastError)")
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to body
    }

    private fun apiBaseUrlCandidates(): List<String> {
        val base = apiBaseUrlLanPrimary.trimEnd('/')
        val noApi =
            if (base.endsWith("/api")) {
                base.removeSuffix("/api").trimEnd('/')
            } else {
                base
            }
        return listOf(base, "$noApi/api").distinct()
    }
}
