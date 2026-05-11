package com.example.osislogin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.osislogin.data.AppDatabase
import com.example.osislogin.util.SessionManager

private val AjaColorScheme = lightColorScheme(
    primary = Color(0xFF1F4A7D),
    onPrimary = Color.White,
    secondary = Color(0xFF16B8B0),
    onSecondary = Color.White,
    tertiary = Color(0xFF0FA9A7),
    background = Color(0xFFF4FAFB),
    onBackground = Color(0xFF23313D),
    surface = Color.White,
    onSurface = Color(0xFF23313D),
    surfaceVariant = Color(0xFFEAF7F8),
    onSurfaceVariant = Color(0xFF526477),
    error = Color(0xFFB3261E)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val database = remember { AppDatabase.getDatabase(applicationContext) }
            val sessionManager = remember { SessionManager(applicationContext) }

            MaterialTheme(colorScheme = AjaColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                            database = database,
                            sessionManager = sessionManager,
                            startDestination = Route.Login.route
                    )
                }
            }
        }
    }
}
