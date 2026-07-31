package com.wirekey.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun WireKeyNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "pairing") {
        composable("pairing") {
            com.wirekey.ui.pairing.PairingScreen(
                onNavigateToLiveMode = {
                    navController.navigate("live_mode") {
                        popUpTo("pairing") { inclusive = false }
                    }
                },
                onNavigateToComposeMode = {
                    navController.navigate("compose_mode") {
                        popUpTo("pairing") { inclusive = false }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable("live_mode") {
            com.wirekey.ui.livemode.LiveModeScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable("compose_mode") {
            com.wirekey.ui.composemode.ComposeModeScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateBack = { navController.popBackStack("pairing", false) }
            )
        }
        composable("settings") {
            com.wirekey.ui.settings.SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = title)
    }
}
