package com.qalaarikha.assistant.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qalaarikha.assistant.ui.home.HomeScreen
import com.qalaarikha.assistant.ui.settings.SettingsScreen

private const val ROUTE_HOME     = "home"
private const val ROUTE_SETTINGS = "settings"

/**
 * Root navigation graph.
 * Two destinations: Home (assistant) and Settings.
 */
@Composable
fun AppNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_HOME) {

        composable(ROUTE_HOME) {
            HomeScreen(
                onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
