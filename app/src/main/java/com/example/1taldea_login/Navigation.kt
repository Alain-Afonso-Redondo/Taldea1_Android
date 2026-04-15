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
    object Categories : Route("categories/{tableId}/{komensalak}/{erreserbaId}/{data}/{txanda}") {
        fun create(tableId: Int, komensalak: Int, erreserbaId: Int?, data: String, txanda: String) =
                "categories/$tableId/$komensalak/${erreserbaId ?: -1}/$data/$txanda"
    }
    object Platerak : Route("platerak/{tableId}/{fakturaId}/{kategoriId}/{komensalak}/{erreserbaId}/{data}/{txanda}") {
        fun create(
            tableId: Int,
            fakturaId: Int,
            kategoriId: Int,
            komensalak: Int,
            erreserbaId: Int?,
            data: String,
            txanda: String
        ) =
                "platerak/$tableId/$fakturaId/$kategoriId/$komensalak/${erreserbaId ?: -1}/$data/$txanda"
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
                    onTableClick = { tableId, komensalak, erreserbaId, data, txanda ->
                        navController.navigate(Route.Categories.create(tableId, komensalak, erreserbaId, data, txanda))
                    }
            )
        }

        composable(
                route = Route.Categories.route,
                arguments = listOf(
                    navArgument("tableId") { type = NavType.IntType },
                    navArgument("komensalak") { type = NavType.IntType },
                    navArgument("erreserbaId") { type = NavType.IntType },
                    navArgument("data") { type = NavType.StringType },
                    navArgument("txanda") { type = NavType.StringType }
                )
        ) { backStackEntry ->
            val tableId = backStackEntry.arguments?.getInt("tableId") ?: 0
            val komensalak = backStackEntry.arguments?.getInt("komensalak") ?: 1
            val erreserbaId = (backStackEntry.arguments?.getInt("erreserbaId") ?: -1).takeIf { it > 0 }
            val data = backStackEntry.arguments?.getString("data").orEmpty()
            val txanda = backStackEntry.arguments?.getString("txanda").orEmpty()
            val viewModel = remember { CategoriesViewModel(sessionManager) }
            CategoriesScreen(
                    tableId = tableId,
                    komensalak = komensalak,
                    erreserbaId = erreserbaId,
                    data = data,
                    txanda = txanda,
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    onChat = { navController.navigate(Route.Chat.route) },
                    chatUnreadCount = chatUiState.unreadCount,
                    onBack = { navController.popBackStack() },
                    onCategorySelected = { tId, fakturaId, kategoriId, selectedKomensalak, selectedErreserbaId, selectedData, selectedTxanda ->
                        navController.navigate(
                            Route.Platerak.create(
                                tId,
                                fakturaId,
                                kategoriId,
                                selectedKomensalak,
                                selectedErreserbaId,
                                selectedData,
                                selectedTxanda
                            )
                        )
                    }
            )
        }

        composable(
                route = Route.Platerak.route,
                arguments =
                        listOf(
                                navArgument("tableId") { type = NavType.IntType },
                                navArgument("fakturaId") { type = NavType.IntType },
                                navArgument("kategoriId") { type = NavType.IntType },
                                navArgument("komensalak") { type = NavType.IntType },
                                navArgument("erreserbaId") { type = NavType.IntType },
                                navArgument("data") { type = NavType.StringType },
                                navArgument("txanda") { type = NavType.StringType }
                        )
        ) { backStackEntry ->
            val tableId = backStackEntry.arguments?.getInt("tableId") ?: 0
            val fakturaId = backStackEntry.arguments?.getInt("fakturaId") ?: 0
            val kategoriId = backStackEntry.arguments?.getInt("kategoriId") ?: 0
            val komensalak = backStackEntry.arguments?.getInt("komensalak") ?: 1
            val erreserbaId = (backStackEntry.arguments?.getInt("erreserbaId") ?: -1).takeIf { it > 0 }
            val data = backStackEntry.arguments?.getString("data").orEmpty()
            val txanda = backStackEntry.arguments?.getString("txanda").orEmpty()
            val viewModel = remember { PlaterakViewModel(sessionManager) }
            PlaterakScreen(
                    tableId = tableId,
                    fakturaId = fakturaId,
                    kategoriId = kategoriId,
                    komensalak = komensalak,
                    erreserbaId = erreserbaId,
                    data = data,
                    txanda = txanda,
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    onChat = { navController.navigate(Route.Chat.route) },
                    chatUnreadCount = chatUiState.unreadCount,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Route.Categories.create(tableId, komensalak, erreserbaId, data, txanda)) {
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
