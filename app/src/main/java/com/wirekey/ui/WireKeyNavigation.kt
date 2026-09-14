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
import com.wirekey.WireKeyApp
import com.wirekey.cloud.CloudAuthState
import com.wirekey.cloud.SupabaseConfig

@Composable
fun WireKeyNavigation() {
    val navController = rememberNavController()

    /**
     * "Account" means the sign-in form to a signed-out user and the account page to a signed-in
     * one. Deciding it here keeps both callers — Compose Mode and Settings — from having to
     * know about auth state at all.
     */
    val navigateToAccount = {
        val signedIn = SupabaseConfig.isConfigured &&
            WireKeyApp.cloudAuthRepository.authState.value is CloudAuthState.SignedIn
        navController.navigate(if (signedIn) "account" else "auth")
    }

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
                },
                onNavigateToAccount = { navigateToAccount() }
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
                onNavigateBack = { navController.popBackStack("pairing", false) },
                onNavigateToAccount = { navigateToAccount() }
            )
        }
        composable("settings") {
            com.wirekey.ui.settings.SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAccount = { navigateToAccount() }
            )
        }
        composable("auth") {
            com.wirekey.ui.cloud.AuthScreen(
                onNavigateBack = { navController.popBackStack() },
                // Replaces itself in the back stack: once signed in, "back" should return to
                // whatever screen sent the user here, not to the login form.
                onSignedIn = { navController.popBackStack() }
            )
        }
        composable("account") {
            com.wirekey.ui.cloud.AccountScreen(
                onNavigateBack = { navController.popBackStack() },
                onSignedOut = {
                    navController.navigate("auth") {
                        popUpTo("account") { inclusive = true }
                    }
                }
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
