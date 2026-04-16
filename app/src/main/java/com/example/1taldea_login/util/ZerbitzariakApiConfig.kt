package com.example.osislogin.util

import com.example.osislogin.BuildConfig

object ZerbitzariakApiConfig {
    private const val emulatorBaseUrl = "http://10.0.2.2:5093/api"

    val primaryBaseUrl: String
        get() = normalizedCandidates().first()

    fun baseUrlCandidates(): List<String> = normalizedCandidates()

    private fun normalizedCandidates(): List<String> {
        val configured = BuildConfig.ZERBITZARIAK_API_BASE.trim()
        return listOf(configured, emulatorBaseUrl)
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
