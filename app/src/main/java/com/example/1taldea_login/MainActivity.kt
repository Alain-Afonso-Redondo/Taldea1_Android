package com.example.osislogin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.osislogin.data.AppDatabase
import com.example.osislogin.util.SessionManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val database = remember { AppDatabase.getDatabase(applicationContext) }
            val sessionManager = remember { SessionManager(applicationContext) }

            MaterialTheme {
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
