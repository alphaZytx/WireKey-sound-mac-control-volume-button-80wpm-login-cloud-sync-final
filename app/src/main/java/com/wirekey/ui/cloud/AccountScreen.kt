package com.wirekey.ui.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wirekey.cloud.CloudAuthState
import com.wirekey.cloud.SupabaseConfig
import com.wirekey.cloud.SyncStatus

/**
 * The signed-in half of the account flow: who you are, what sync is currently doing, and the
 * way out. Signing out here also tears down the realtime subscription (via the auth state the
 * whole cloud layer watches) — it does not touch the Bluetooth connection or a send in flight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onNavigateBack: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: CloudAccountViewModel = viewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()

    // Covers both pressing Sign out here and the session ending elsewhere (expired refresh
    // token, signed out on another device) — either way this screen has nothing left to show.
    LaunchedEffect(authState) {
        if (authState is CloudAuthState.SignedOut) onSignedOut()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("Back") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            if (!SupabaseConfig.isConfigured) {
                Box(Modifier.padding(24.dp)) { NotConfiguredCard() }
                return@Column
            }

            when (val state = authState) {
                is CloudAuthState.SignedIn -> {
                    ListItem(
                        headlineContent = { Text(state.email ?: "Signed in") },
                        supportingContent = { Text("Supabase account") }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Compose sync") },
                        supportingContent = { Text(syncStatusDetail(syncStatus)) },
                        trailingContent = {
                            Text(
                                text = syncStatusLabel(syncStatus),
                                style = MaterialTheme.typography.labelMedium,
                                color = syncStatusColor(syncStatus)
                            )
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("User ID") },
                        supportingContent = {
                            Text(
                                text = state.userId,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )

                    Spacer(Modifier.height(24.dp))

                    if (error != null) {
                        Text(
                            text = error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Button(
                        onClick = { viewModel.signOut() },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    ) {
                        Text("Sign out")
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Signing out stops cloud sync on this phone. The text " +
                            "currently in Compose Mode stays where it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                is CloudAuthState.Loading -> {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Checking your session…")
                    }
                }

                // SignedOut is handled by the LaunchedEffect above; NotConfigured by the
                // early return. Neither renders anything here.
                else -> Unit
            }
        }
    }
}

// ── Shared sync-status rendering ─────────────────────────────────────────────
// Kept here so Compose Mode's sync bar and this screen can never disagree about what a
// given status is called.

/** The short label the spec asks for: Synced / Syncing… / Sync failed. */
internal fun syncStatusLabel(status: SyncStatus): String = when (status) {
    is SyncStatus.NotConfigured -> "Sync off"
    is SyncStatus.SignedOut -> "Not signed in"
    is SyncStatus.Connecting -> "Connecting…"
    is SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Synced -> "Synced"
    is SyncStatus.Failed -> "Sync failed"
}

internal fun syncStatusDetail(status: SyncStatus): String = when (status) {
    is SyncStatus.NotConfigured -> "No Supabase project in this build"
    is SyncStatus.SignedOut -> "Sign in to sync"
    is SyncStatus.Connecting -> "Opening the realtime channel"
    is SyncStatus.Syncing -> "Talking to Supabase"
    is SyncStatus.Synced -> "Up to date with the cloud copy"
    is SyncStatus.Failed -> status.message
}

@Composable
internal fun syncStatusColor(status: SyncStatus): androidx.compose.ui.graphics.Color = when (status) {
    is SyncStatus.Failed -> MaterialTheme.colorScheme.error
    is SyncStatus.Synced -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
