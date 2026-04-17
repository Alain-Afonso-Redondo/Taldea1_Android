    package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.data.AppDatabase
import com.example.osislogin.util.HashingUtil
import com.example.osislogin.util.SessionManager
import com.example.osislogin.util.ZerbitzariakApiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.ConnectException
import java.net.SocketTimeoutException

data class ApiUser(
    val id: Int,
    val username: String,
    val displayName: String = username,
    val lanpostuaId: Int? = null,
    val langileaId: Int? = null,

)

data class LangileInfo(
    val id: Int,
    val displayName: String,
    val lanpostuaId: Int?
)

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val pin: String = "",
    val selectedUserId: Int? = null,
    val users: List<ApiUser> = emptyList(),
    val showPinDialog: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

class LoginViewModel(
    private val database: AppDatabase,
    private val sessionManager: SessionManager
) : ViewModel() {   

    private val apiBaseUrlLanPrimary = ZerbitzariakApiConfig.primaryBaseUrl



    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun updatePin(pin: String) {
        _uiState.value = _uiState.value.copy(pin = pin)
    }

    fun loadUsers() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val users = withContext(Dispatchers.IO) { fetchUsersFromApi() }
                _uiState.value = _uiState.value.copy(users = users, isLoading = false)
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
                    error = "Error al cargar usuarios$suffix: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun selectUser(user: ApiUser) {
        _uiState.value = _uiState.value.copy(
            selectedUserId = user.id,
            showPinDialog = true,
            error = null
        )
    }

    fun verifyPin() {
        viewModelScope.launch {
            val selectedUserId = _uiState.value.selectedUserId
            if (selectedUserId == null) {
                _uiState.value = _uiState.value.copy(error = "No se seleccionó usuario")
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val user = database.userDao().getUserById(selectedUserId)
                
                if (user != null) {
                    val hashingUtil = HashingUtil()
                    if (hashingUtil.verifyPassword(_uiState.value.pin, user.pin)) {
                        sessionManager.saveUserSession(user.email, user.fullName, user.id)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isSuccess = true,
                            showPinDialog = false
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "PIN incorrecto",
                            pin = ""
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Error al verificar PIN"
                )
            }
        }
    }

    /**
     * LOGIN CON API PARA SISTEMA DE BOTONES + PIN
     * Usa el email del usuario seleccionado y el PIN como contraseña
     */
    fun verifyPinWithApi() {
        viewModelScope.launch {
            val selectedUserId = _uiState.value.selectedUserId
            if (selectedUserId == null) {
                _uiState.value = _uiState.value.copy(error = "No se seleccionó usuario")
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val user = _uiState.value.users.firstOrNull { it.id == selectedUserId }
                if (user == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                    return@launch
                }

                val erabiltzailea = user.username
                val pasahitza = _uiState.value.pin

                val result = withContext(Dispatchers.IO) { postLogin(erabiltzailea, pasahitza) }
                if (result.rolaId != 2) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Erabiltzaile honek ez dauka aplikazio honetan sartzeko baimenik"
                    )
                    return@launch
                }

                sessionManager.saveUserSession(
                    result.userName.ifBlank { erabiltzailea },
                    result.displayName.ifBlank { user.displayName },
                    user.id,
                    canAccessChat = result.txatEnabled
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isSuccess = true,
                    showPinDialog = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error de conexión"
                )
            }
        }
    }

    fun cancelPinDialog() {
        _uiState.value = _uiState.value.copy(
            showPinDialog = false,
            pin = "",
            selectedUserId = null,
            error = null
        )
    }

    fun login() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val user = database.userDao().getUserByEmail(_uiState.value.email)
                
                if (user != null) {
                    val hashingUtil = HashingUtil()
                    if (hashingUtil.verifyPassword(_uiState.value.password, user.password)) {
                        sessionManager.saveUserSession(user.email, user.fullName, user.id)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isSuccess = true
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Credenciales inválidas"
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Usuario no encontrado"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Error desconocido"
                )
            }
        }
    }

    /**
     * LOGIN CON API C# (HTTP POST JSON).
     * Intenta login con API primero, si falla usa login local
     */
    fun loginWithApi() {
        val erabiltzailea = _uiState.value.email.trim()
        val pasahitza = _uiState.value.password

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = withContext(Dispatchers.IO) { postLogin(erabiltzailea, pasahitza) }
                if (result.rolaId != 2) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Erabiltzaile honek ez dauka aplikazio honetan sartzeko baimenik"
                    )
                    return@launch
                }

                sessionManager.saveUserSession(
                    result.userName.ifBlank { erabiltzailea },
                    result.displayName.ifBlank { result.userName.ifBlank { erabiltzailea } },
                    result.userId,
                    canAccessChat = result.txatEnabled
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isSuccess = true,
                    error = null
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
                    error = "${e.message ?: "Error de conexión"}$suffix"
                )
            }
        }
    }

    fun resetSuccess() {
        _uiState.value = _uiState.value.copy(isSuccess = false)
    }

    private fun fetchUsersFromApi(): List<ApiUser> {
        var lastException: Exception? = null

        for (baseUrl in apiBaseUrlCandidates()) {
            val candidateUrls = listOf(
                "$baseUrl/erabiltzaileak/login",
                "$baseUrl/Erabiltzaileak/login",
                "$baseUrl/Erabiltzailea",
                "$baseUrl/erabiltzailea"
            )

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
                        lastException = IllegalStateException("HTTP $code $candidateUrl helbidean")
                        continue
                    }

                    val users = parseUsers(body)
                    if (users.isEmpty()) continue
                    return users
                } catch (e: Exception) {
                    lastException = IllegalStateException("Ezin izan da konektatu $candidateUrl: ${e.message ?: e.javaClass.simpleName}", e)
                }
            }
        }

        throw lastException ?: IllegalStateException("Ezin izan dira erabiltzaileak kargatu")
    }

    private fun parseUsers(body: String): List<ApiUser> {
        val root = JSONTokener(body).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                root.optJSONArray("users")
                    ?: root.optJSONArray("datuak")
                    ?: root.optJSONArray("Datuak")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("result")
                    ?: root.optJSONArray("erabiltzaileak")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }

        val result = ArrayList<ApiUser>(array.length())
        for (i in 0 until array.length()) {
            val element = array.opt(i)
            when (element) {
                is JSONObject -> {
                    val id = element.optInt("id", i + 1)
                    val username = element.optString(
                        "izena",
                        element.optString(
                            "Izena",
                            element.optString(
                                "erabiltzailea",
                                element.optString(
                                    "Erabiltzailea",
                                    element.optString(
                                        "username",
                                        element.optString(
                                            "email",
                                            element.optString("emaila", element.optString("name", ""))
                                        )
                                    )
                                )
                            )
                        )
                    )
                    if (username.isBlank()) continue

                    val langilea = element.optJSONObject("langilea") ?: element.optJSONObject("Langilea")
                    val langileaId = when {
                        langilea != null -> langilea.optInt("id", langilea.optInt("Id", -1)).takeIf { it > 0 }
                        else -> element.optInt("langileak_id", element.optInt("langileaId", element.optInt("LangileaId", -1))).takeIf { it > 0 }
                    }

                    val lanpostua = langilea?.optJSONObject("lanpostua") ?: langilea?.optJSONObject("Lanpostua")
                    val lanpostuaId = when {
                        lanpostua != null -> lanpostua.optInt("id", lanpostua.optInt("Id", -1)).takeIf { it > 0 }
                        else -> element.optInt(
                            "id_lanpostua",
                            element.optInt(
                                "lanpostuaId",
                                element.optInt(
                                    "LanpostuaId",
                                    element.optInt("lanpostua_id", -1)
                                )
                            )
                        ).takeIf { it > 0 }
                    }

                    val displayName = if (langilea != null) {
                        langilea.optString("izena", langilea.optString("Izena", "")).trim().ifBlank { username }
                    } else {
                        element.optString(
                            "displayName",
                            element.optString(
                                "fullName",
                                element.optString(
                                    "full_name",
                                    element.optString(
                                        "erabiltzailea",
                                        element.optString("Erabiltzailea", username)
                                    )
                                )
                            )
                        )
                    }

                    result.add(
                        ApiUser(
                            id = id,
                            username = username,
                            displayName = displayName,
                            lanpostuaId = lanpostuaId,
                            langileaId = langileaId
                        )
                    )
                }
                is String -> {
                    if (element.isNotBlank()) {
                        result.add(ApiUser(id = i + 1, username = element, displayName = element))
                    }
                }
            }
        }

        return result
    }

    private fun fetchLangileakFromApi(): Map<Int, LangileInfo> {
        var lastException: Exception? = null

        for (baseUrl in apiBaseUrlCandidates()) {
            val candidateUrls = listOf(
                "$baseUrl/Langilea",
                "$baseUrl/langilea"
            )

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
                        lastException = IllegalStateException("HTTP $code $candidateUrl helbidean")
                        continue
                    }

                    val list = parseLangileak(body)
                    if (list.isEmpty()) continue
                    return list.associateBy { it.id }
                } catch (e: Exception) {
                    lastException = IllegalStateException("Ezin izan da konektatu $candidateUrl: ${e.message ?: e.javaClass.simpleName}", e)
                }
            }
        }
        throw lastException ?: IllegalStateException("Ezin izan dira langileak kargatu")
    }

    private fun parseLangileak(body: String): List<LangileInfo> {
        val root = JSONTokener(body).nextValue()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("result") ?: JSONArray()
            else -> JSONArray()
        }

        val result = ArrayList<LangileInfo>(array.length())
        for (i in 0 until array.length()) {
            val element = array.optJSONObject(i) ?: continue
            val id = element.optInt("id", element.optInt("Id", -1))
            if (id <= 0) continue

            val displayName = element
                .optString("izena", element.optString("Izena", ""))
                .trim()
                .ifBlank { id.toString() }

            val lanpostua = element.optJSONObject("lanpostua") ?: element.optJSONObject("Lanpostua")
            val lanpostuaId = when {
                lanpostua != null -> lanpostua.optInt("id", lanpostua.optInt("Id", -1)).takeIf { it > 0 }
                else -> element.optInt("id_lanpostua", element.optInt("Id_Lanpostua", -1)).takeIf { it > 0 }
            }

            result.add(LangileInfo(id = id, displayName = displayName, lanpostuaId = lanpostuaId))
        }

        return result
    }

    private data class LoginApiResult(
        val userId: Int?,
        val userName: String,
        val displayName: String,
        val rolaId: Int?,
        val txatEnabled: Boolean
    )

    private fun postLogin(erabiltzailea: String, pasahitza: String): LoginApiResult {
        var lastException: Exception? = null
        val candidateUrls =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/login",
                    "$baseUrl/Login"
                )
            }.distinct()

        for (candidateUrl in candidateUrls) {
            try {
                val url = URL(candidateUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val requestBody = JSONObject()
                    .put("erabiltzailea", erabiltzailea)
                    .put("pasahitza", pasahitza)
                    .toString()

                conn.outputStream.use { os ->
                    os.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code == 401) {
                    throw IllegalStateException("Erabiltzaile edo pasahitz okerra")
                }

                if (code !in 200..299) {
                    lastException = IllegalStateException("HTTP $code $candidateUrl helbidean: ${body.take(200)}")
                    continue
                }

                val root = JSONTokener(body).nextValue() as? JSONObject
                    ?: throw IllegalStateException("Login erantzun baliogabea")
                val dataArray = root.optJSONArray("datuak") ?: root.optJSONArray("Datuak") ?: JSONArray()
                val userObject = dataArray.optJSONObject(0)
                    ?: throw IllegalStateException("Login erantzunak ez du erabiltzaile daturik")
                val userId =
                    userObject.optInt(
                        "id",
                        userObject.optInt("Id", -1)
                    ).takeIf { it > 0 }
                val rolaObject = userObject.optJSONObject("rola") ?: userObject.optJSONObject("Rola")
                val rolaId =
                    rolaObject?.optInt("id", rolaObject.optInt("Id", -1))?.takeIf { it > 0 }
                        ?: userObject.optInt("rolaId", userObject.optInt("RolaId", -1)).takeIf { it > 0 }
                val txatEnabled = parseTxatEnabled(userObject)

                val userName =
                    userObject.optString(
                        "erabiltzailea",
                        userObject.optString("Erabiltzailea", "")
                    ).trim()
                val email =
                    userObject.optString(
                        "emaila",
                        userObject.optString("Emaila", "")
                    ).trim()

                val displayName = userName.ifBlank { email }
                if (displayName.isBlank()) {
                    throw IllegalStateException("Login erantzunak ez du erabiltzaile izenik")
                }

                return LoginApiResult(
                    userId = userId,
                    userName = userName.ifBlank { email },
                    displayName = displayName,
                    rolaId = rolaId,
                    txatEnabled = txatEnabled
                )
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: IllegalStateException("Ezin izan da loginera konektatu")
    }

    private fun apiBaseUrlCandidates(): List<String> {
        return ZerbitzariakApiConfig.baseUrlCandidates().flatMap { base ->
            val noApi =
                if (base.endsWith("/api")) {
                    base.removeSuffix("/api").trimEnd('/')
                } else {
                    base
                }
            listOf(base, noApi)
        }.distinct()
    }

    private fun parseTxatEnabled(userObject: JSONObject): Boolean {
        val directValue = userObject.opt("txat") ?: userObject.opt("Txat")
        when (directValue) {
            is Boolean -> return directValue
            is Number -> return directValue.toInt() == 1
            is String -> {
                val normalized = directValue.trim().lowercase()
                if (normalized in setOf("true", "1", "bai", "yes")) return true
                if (normalized in setOf("false", "0", "ez", "no", "")) return false
            }
        }

        val txatObject = userObject.optJSONObject("txat") ?: userObject.optJSONObject("Txat")
        if (txatObject != null) {
            val nestedValue = txatObject.opt("enabled")
                ?: txatObject.opt("Enabled")
                ?: txatObject.opt("baimenduta")
                ?: txatObject.opt("Baimenduta")
                ?: txatObject.opt("balioa")
                ?: txatObject.opt("Balioa")
            when (nestedValue) {
                is Boolean -> return nestedValue
                is Number -> return nestedValue.toInt() == 1
                is String -> return nestedValue.trim().equals("true", ignoreCase = true) || nestedValue.trim() == "1"
            }
        }

        return false
    }
}
