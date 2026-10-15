@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.account

import android.graphics.BitmapFactory
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.account.AccountUser
import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.cloud.CloudSyncStatus
import com.enjoyyourtime.globalfitness.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Optional Google account and cloud backup. No ads are shown on this screen. */
@Composable
fun AccountScreen(onBack: () -> Unit, vm: AccountViewModel = viewModel(factory = AccountViewModel.Factory)) {
    val user by vm.user.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val snackbar = remember { SnackbarHostState() }

    SuppressInterstitials(AdSuppression.ACCOUNT_SCREEN)
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cuenta y copia en la nube") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val signedIn = user
            if (signedIn == null) {
                SignedOut(busy = busy == AccountAction.SIGN_IN, onSignIn = { activity?.let(vm::signIn) })
            } else {
                SignedIn(
                    user = signedIn,
                    status = status,
                    busy = busy,
                    onSync = vm::syncNow,
                    onSignOut = vm::signOut,
                    onDelete = { activity?.let(vm::deleteCloudDataAndAccount) },
                )
            }
        }
    }
}

@Composable
private fun SignedOut(busy: Boolean, onSignIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            Icons.Filled.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("Copia de seguridad en la nube", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Guarda tus datos en la nube y recupéralos si cambias de móvil o reinstalas la app.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Benefit("Tu perfil, tus ajustes y tus mediciones se guardan en tu cuenta, en servidores de Google en la UE (Madrid).")
        Benefit("Se sincroniza sola cuando hay conexión; sin conexión todo sigue funcionando.")
        Benefit("Es opcional: sin cuenta, la app funciona igual y los datos se quedan solo en este teléfono.")
        Benefit("Puedes borrar tus datos de la nube y la cuenta cuando quieras.")
        Text(
            "Nunca se suben los datos de Health Connect ni la clave de la pulsera.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Iniciar sesión con Google")
        }
    }
}

@Composable
private fun Benefit(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SignedIn(
    user: AccountUser,
    status: CloudSyncStatus,
    busy: AccountAction?,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountPhoto(user.photoUrl, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.name ?: "Cuenta de Google", style = MaterialTheme.typography.titleMedium)
                user.email?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sincronización", style = MaterialTheme.typography.titleSmall)
            SyncStatusLine(status)
            FilledTonalButton(onClick = onSync, enabled = !status.syncing && busy == null) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sincronizar ahora")
            }
        }
    }

    OutlinedButton(onClick = { confirmSignOut = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
        Text("Cerrar sesión")
    }
    TextButton(
        onClick = { confirmDelete = true },
        enabled = busy == null,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        if (busy == AccountAction.DELETE) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
        }
        Text("Borrar mis datos de la nube y la cuenta")
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Tus datos se quedan en este teléfono, pero dejarán de copiarse en la nube hasta que vuelvas a iniciar sesión.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("Cerrar sesión") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancelar") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar tus datos de la nube y tu cuenta?") },
            text = {
                Text(
                    "Se borrarán para siempre tu copia en la nube (perfil, ajustes y mediciones) y tu cuenta de " +
                        "GlobalFitness. Los datos de este teléfono no se borran.\n\n" +
                        "Por seguridad, Google te pedirá que confirmes la cuenta.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Borrar definitivamente") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SyncStatusLine(status: CloudSyncStatus) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        status.syncing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Sincronizando…", style = MaterialTheme.typography.bodyMedium)
        }
        else -> Text(
            status.lastSyncAt?.let { "Última sincronización: ${Fmt.dateTime(it)}" } ?: "Aún no se ha sincronizado.",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
    }
    if (status.pendingUpload) {
        Text(
            "Hay cambios guardados que se subirán cuando haya conexión.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
    status.error?.let { error ->
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (status.retrying) "$error (reintentando)" else error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Google profile photo, loaded without an image library; falls back to a generic icon. */
@Composable
private fun AccountPhoto(url: String?, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { loadBitmap(it) }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(size), tint = MaterialTheme.colorScheme.primary)
    }
}

private suspend fun loadBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            connection.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    } catch (e: java.io.IOException) {
        null
    }
}

/** Holds interstitials off while this screen is visible. */
@Composable
internal fun SuppressInterstitials(reason: String) {
    val suppression = (LocalContext.current.applicationContext as GlobalFitnessApp)
        .container.adSuppression
    DisposableEffect(reason) {
        suppression.set(reason, true)
        onDispose { suppression.set(reason, false) }
    }
}
