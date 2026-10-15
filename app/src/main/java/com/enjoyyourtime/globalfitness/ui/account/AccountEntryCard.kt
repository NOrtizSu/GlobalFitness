package com.enjoyyourtime.globalfitness.ui.account

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.premium.RemoveAdsScreen

private enum class EntryScreen { NONE, ACCOUNT, REMOVE_ADS }

/**
 * TEMPORARY entry point (placed in Perfiles) to reach the account / cloud backup and
 * "Quitar anuncios" screens, plus "Opciones de privacidad de anuncios" when UMP requires it.
 * The navigation redesign replaces this card with proper destinations.
 */
@Composable
fun AccountAndPremiumEntry(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as GlobalFitnessApp).container
    val user by container.account.user.collectAsStateWithLifecycle()
    val sync by container.cloudSync.status.collectAsStateWithLifecycle()
    val premium by container.billing.isPremium.collectAsStateWithLifecycle()
    val privacyOptionsRequired by container.ads.privacyOptionsRequired.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var open by rememberSaveable { mutableStateOf(EntryScreen.NONE) }

    OutlinedCard(modifier.fillMaxWidth()) {
        Column {
            val signedIn = user
            val lastSync = sync.lastSyncAt
            ListItem(
                headlineContent = { Text("Cuenta y copia en la nube") },
                supportingContent = {
                    val who = signedIn?.email ?: "Conectada"
                    Text(
                        when {
                            signedIn == null -> "Sin cuenta: los datos solo están en este teléfono"
                            sync.conflict != null || sync.deletionPending -> "$who · sincronización en pausa, toca para elegir"
                            sync.error != null -> "$who · error de sincronización"
                            lastSync != null -> "$who · ${Fmt.dateTime(lastSync)}"
                            else -> who
                        },
                    )
                },
                leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                modifier = Modifier.clickable { open = EntryScreen.ACCOUNT },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Quitar anuncios") },
                supportingContent = { Text(if (premium) "Activo: sin anuncios" else "Pago único, sin suscripción") },
                leadingContent = { Icon(Icons.Filled.Star, contentDescription = null) },
                modifier = Modifier.clickable { open = EntryScreen.REMOVE_ADS },
            )
            if (privacyOptionsRequired) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Opciones de privacidad de anuncios") },
                    leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    modifier = Modifier.clickable {
                        activity?.let { a ->
                            container.ads.showPrivacyOptions(a) { error ->
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
        }
    }

    when (open) {
        EntryScreen.NONE -> Unit
        EntryScreen.ACCOUNT -> FullScreen(onClose = { open = EntryScreen.NONE }) {
            AccountScreen(onBack = { open = EntryScreen.NONE })
        }
        EntryScreen.REMOVE_ADS -> FullScreen(onClose = { open = EntryScreen.NONE }) {
            RemoveAdsScreen(onBack = { open = EntryScreen.NONE })
        }
    }
}

@Composable
private fun FullScreen(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}
