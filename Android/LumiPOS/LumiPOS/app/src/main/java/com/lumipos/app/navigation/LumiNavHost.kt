package com.lumipos.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumipos.app.MainViewModel
import com.lumipos.app.StartDestination
import com.lumipos.app.ui.activation.ActivationScreen
import com.lumipos.app.ui.login.LoginScreen
import com.lumipos.app.ui.main.MainScreen
import com.lumipos.app.ui.setup.SetupAdminScreen

@Composable
fun LumiNavHost(
    mainViewModel: MainViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val startDestination by mainViewModel.startDestination.collectAsState()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination

    if (startDestination == StartDestination.Loading) return

    val destinationRoute: Any? = when (startDestination) {
        StartDestination.Activation -> ActivationRoute
        StartDestination.Setup -> SetupRoute
        StartDestination.Login -> LoginRoute
        StartDestination.Main -> MainRoute
        StartDestination.Loading -> null
    }
    if (destinationRoute == null) return

    NavHost(
        navController = navController,
        startDestination = destinationRoute
    ) {
        composable<ActivationRoute> {
            ActivationScreen()
        }
        composable<SetupRoute> {
            SetupAdminScreen(
                onSetupComplete = {
                    navController.navigate(LoginRoute) {
                        popUpTo(SetupRoute) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable<LoginRoute> {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(MainRoute) {
                        popUpTo(LoginRoute) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable<MainRoute> {
            MainScreen()
        }
    }

    LaunchedEffect(startDestination) {
        val route: Any = when (startDestination) {
            StartDestination.Activation -> ActivationRoute
            StartDestination.Setup -> SetupRoute
            StartDestination.Login -> LoginRoute
            StartDestination.Main -> MainRoute
            StartDestination.Loading -> return@LaunchedEffect
        }
        if (currentDestination?.hasRoute(route::class) == true) return@LaunchedEffect
        navController.navigate(route) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}