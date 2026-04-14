package com.example.osislogin

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.osislogin.data.AppDatabase
import com.example.osislogin.ui.CategoriesScreen
import com.example.osislogin.ui.CategoriesViewModel
import com.example.osislogin.ui.ChatScreen
import com.example.osislogin.ui.ChatViewModel
import com.example.osislogin.ui.HomeScreen
import com.example.osislogin.ui.HomeViewModel
import com.example.osislogin.ui.LoginScreen
import com.example.osislogin.ui.LoginViewModel
import com.example.osislogin.ui.PlaterakScreen
import com.example.osislogin.ui.PlaterakViewModel
import com.example.osislogin.util.SessionManager
import kotlinx.coroutines.launch

sealed class Route(val route: String) {
    object Login : Route("login")
    object Home : Route("home")
    object Categories : Route("categories/{tableId}") {
        fun create(tableId: Int) = "categories/$tableId"
    }
    object Platerak : Route("platerak/{tableId}/{fakturaId}/{kategoriId}") {
        fun create(tableId: Int, fakturaId: Int, kategoriId: Int) =
                "platerak/$tableId/$fakturaId/$kategoriId"
    }
    object Chat : Route("chat")
}

@Composable
fun AppNavigation(database: AppDatabase, sessionManager: SessionManager, startDestination: String) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext

    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(initialUserName = "Anonimoa"))
    val chatUiState by chatViewModel.uiState.collectAsState()
    val userName by sessionManager.userName.collectAsState(initial = null)

    LaunchedEffect(userName) {
        val name = userName?.trim().orEmpty()
        if (name.isNotBlank()) {
            chatViewModel.updateUserName(name)
            chatViewModel.connect()
        }
    }

    val logoutAndGoToLogin: () -> Unit = {
        scope.launch { sessionManager.clearSession() }
        chatViewModel.reset()
        navController.navigate(Route.Login.route) { popUpTo(Route.Home.route) { inclusive = true } }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Route.Login.route) {
            val viewModel = remember { LoginViewModel(database, sessionManager) }
            LoginScreen(
                    viewModel = viewModel,
                    onLoginSuccess = {
                        navController.navigate(Route.Home.route) {
                            popUpTo(Route.Login.route) { inclusive = true }
                        }
                    }
            )
        }

        composable(Route.Home.route) {
            val viewModel = remember { HomeViewModel(sessionManager) }
            HomeScreen(
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    onChat = { navController.navigate(Route.Chat.route) },
                    chatUnreadCount = chatUiState.unreadCount,
                    onTableClick = { tableId ->
                        navController.navigate(Route.Categories.create(tableId))
                    }
            )
        }

        composable(
                route = Route.Categories.route,
                arguments = listOf(navArgument("tableId") { type = NavType.IntType })
        ) { backStackEntry ->
            val tableId = backStackEntry.arguments?.getInt("tableId") ?: 0
            val viewModel = remember { CategoriesViewModel() }
            CategoriesScreen(
                    tableId = tableId,
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    onChat = { navController.navigate(Route.Chat.route) },
                    chatUnreadCount = chatUiState.unreadCount,
                    onBack = { navController.popBackStack() },
                    onCategorySelected = { tId, fakturaId, kategoriId ->
                        navController.navigate(Route.Platerak.create(tId, fakturaId, kategoriId))
                    }
            )
        }

        composable(
                route = Route.Platerak.route,
                arguments =
                        listOf(
                                navArgument("tableId") { type = NavType.IntType },
                                navArgument("fakturaId") { type = NavType.IntType },
                                navArgument("kategoriId") { type = NavType.IntType }
                        )
        ) { backStackEntry ->
            val tableId = backStackEntry.arguments?.getInt("tableId") ?: 0
            val fakturaId = backStackEntry.arguments?.getInt("fakturaId") ?: 0
            val kategoriId = backStackEntry.arguments?.getInt("kategoriId") ?: 0
            val viewModel = remember { PlaterakViewModel() }
            PlaterakScreen(
                    tableId = tableId,
                    fakturaId = fakturaId,
                    kategoriId = kategoriId,
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    onChat = { navController.navigate(Route.Chat.route) },
                    chatUnreadCount = chatUiState.unreadCount,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Route.Categories.create(tableId)) {
                                launchSingleTop = true
                            }
                        }
                    }
            )
        }

        composable(Route.Chat.route) {
            ChatScreen(
                    viewModel = chatViewModel,
                    onLogout = logoutAndGoToLogin,
                    onBack = { navController.popBackStack() }
            )
        }
    }
}
