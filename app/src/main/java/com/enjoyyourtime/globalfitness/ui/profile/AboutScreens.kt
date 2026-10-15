@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled from docs/privacy-policy.md at build time (see app/build.gradle.kts). */
private const val POLICY_ASSET = "privacy-policy.md"

private sealed interface PolicyState {
    data object Loading : PolicyState
    data class Loaded(val blocks: List<MdBlock>) : PolicyState
    data object Failed : PolicyState
}

/** The privacy policy (a draft pending legal review, as its own banner says), rendered in-app. */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val policy by produceState<PolicyState>(PolicyState.Loading) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(POLICY_ASSET).bufferedReader().use { it.readText() } }
                .map { PolicyState.Loaded(MarkdownLite.parse(it)) }
                .getOrDefault(PolicyState.Failed)
        }
    }
    SubPage(title = "Política de privacidad", onBack = onBack) { padding ->
        when (val p = policy) {
            PolicyState.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            PolicyState.Failed -> Text(
                "No se pudo abrir la política de privacidad.",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            is PolicyState.Loaded -> LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.padding(padding)) {
                item { MarkdownDocument(p.blocks) }
            }
        }
    }
}

private data class Notice(val name: String, val license: String, val text: String, val url: String)

private val PORTED = listOf(
    Notice(
        name = "openScale",
        license = "GPL-3.0",
        text = "Los protocolos Bluetooth de las básculas y sus fórmulas de composición corporal están portados de openScale.",
        url = "https://github.com/oliexdev/openScale",
    ),
    Notice(
        name = "Gadgetbridge",
        license = "AGPL-3.0",
        text = "El protocolo de la Xiaomi Smart Band (modo directo) está portado de Gadgetbridge.",
        url = "https://codeberg.org/Freeyourgadget/Gadgetbridge",
    ),
)

private val LIBRARIES = listOf(
    Notice("AndroidX, Jetpack Compose y Material 3", "Apache-2.0", "Interfaz, navegación y ciclo de vida.", "https://developer.android.com/jetpack/androidx"),
    Notice("Health Connect (connect-client)", "Apache-2.0", "Lectura y escritura de datos de salud.", "https://developer.android.com/health-and-fitness/guides/health-connect"),
    Notice("Kotlin y kotlinx.coroutines", "Apache-2.0", "Lenguaje y concurrencia.", "https://kotlinlang.org"),
    Notice("Protocol Buffers (javalite)", "BSD-3-Clause", "Mensajes del protocolo de la pulsera.", "https://github.com/protocolbuffers/protobuf"),
    Notice(
        "Firebase, Google Mobile Ads, UMP y Play Billing",
        "Condiciones de Google",
        "Cuenta y copia en la nube, anuncios con consentimiento y la compra «Quitar anuncios».",
        "https://developers.google.com/terms",
    ),
)

/** Open-source notices: ported protocol code first (copyleft), then the libraries. */
@Composable
fun OpenSourceScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SubPage(title = "Código abierto", onBack = onBack) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                Text(
                    "GlobalFitness incluye código portado de proyectos libres. Gracias a sus autores.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            items(PORTED) { NoticeItem(it, onOpen = { runCatching { uriHandler.openUri(it.url) } }) }
            item {
                AppCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        "Nota de licencia: el código portado está bajo GPL-3.0 y AGPL-3.0. Antes de publicar la app hay que " +
                            "decidir su licencia (compatible con ambas, p. ej. AGPL-3.0) y publicar el código fuente " +
                            "correspondiente, o sustituir esas partes.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item {
                Text("Bibliotecas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(LIBRARIES) { NoticeItem(it, onOpen = { runCatching { uriHandler.openUri(it.url) } }) }
        }
    }
}

@Composable
private fun NoticeItem(notice: Notice, onOpen: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(notice.name, style = MaterialTheme.typography.titleMedium)
            Text("Licencia: ${notice.license}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(notice.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abrir web del proyecto") }
        }
    }
}

/** Secondary page with a back arrow (no bottom bar, no ads). */
@Composable
fun SubPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
        content = content,
    )
}
