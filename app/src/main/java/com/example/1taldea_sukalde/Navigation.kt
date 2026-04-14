package com.example.osiskitchen

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.osiskitchen.ui.KitchenIngredientesStockScreen
import com.example.osiskitchen.ui.KitchenIngredientesStockViewModel
import com.example.osiskitchen.ui.KitchenOrdersScreen
import com.example.osiskitchen.ui.KitchenOrdersViewModel
import com.example.osiskitchen.ui.KitchenPlatosStockScreen
import com.example.osiskitchen.ui.KitchenPlatosStockViewModel

sealed class Route(val route: String) {
    object KitchenOrders : Route("kitchen/orders")
    object KitchenPlatosStock : Route("kitchen/platos")
    object KitchenIngredientesStock : Route("kitchen/ingredientes")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Route.KitchenOrders.route) {
        composable(Route.KitchenOrders.route) {
            val viewModel: KitchenOrdersViewModel = viewModel()
            KitchenOrdersScreen(
                viewModel = viewModel,
                onGoPlatos = {
                    navController.navigate(Route.KitchenPlatosStock.route) { launchSingleTop = true }
                },
                onGoIngredientes = {
                    navController.navigate(Route.KitchenIngredientesStock.route) { launchSingleTop = true }
                }
            )
        }

        composable(Route.KitchenPlatosStock.route) {
            val viewModel: KitchenPlatosStockViewModel = viewModel()
            KitchenPlatosStockScreen(
                viewModel = viewModel,
                onGoComandas = {
                    navController.navigate(Route.KitchenOrders.route) { launchSingleTop = true }
                },
                onGoIngredientes = {
                    navController.navigate(Route.KitchenIngredientesStock.route) { launchSingleTop = true }
                }
            )
        }

        composable(Route.KitchenIngredientesStock.route) {
            val viewModel: KitchenIngredientesStockViewModel = viewModel()
            KitchenIngredientesStockScreen(
                viewModel = viewModel,
                onGoComandas = {
                    navController.navigate(Route.KitchenOrders.route) { launchSingleTop = true }
                },
                onGoPlatos = {
                    navController.navigate(Route.KitchenPlatosStock.route) { launchSingleTop = true }
                }
            )
        }
    }
}
