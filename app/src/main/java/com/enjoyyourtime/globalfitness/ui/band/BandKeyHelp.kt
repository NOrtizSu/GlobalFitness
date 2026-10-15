@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.band

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

private const val GB_SERVER_PAIRING = "https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/"
private const val GB_XIAOMI_DEVICES = "https://gadgetbridge.org/gadgets/wearables/xiaomi/"
private const val CLOUD_TOKENS_EXTRACTOR = "https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor"

/** Explains the auth key and the documented ways to obtain it. Nothing here extracts it automatically. */
@Composable
fun BandKeyHelpScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("La clave de autenticación") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Heading("¿Qué es?")
            Paragraph(
                "Es una clave secreta de 32 caracteres hexadecimales (16 bytes) que comparten tu pulsera y tu cuenta Xiaomi. " +
                    "Los servidores de Xiaomi la generan cuando vinculas la pulsera en Mi Fitness. La pulsera la usa para " +
                    "comprobar que el teléfono que se conecta está autorizado y para cifrar la comunicación. " +
                    "No se puede generar en el teléfono: hay que obtenerla de Mi Fitness o de tu cuenta Xiaomi.",
            )

            Heading("Importante")
            Bullet("Si desvinculas la pulsera en Mi Fitness (o la restableces de fábrica), la clave deja de valer. Tras volver a vincularla tendrás que obtener la nueva.")
            Bullet("No compartas la clave: quien la tenga puede conectarse a tu pulsera.")
            Bullet("Esta app la guarda solo en su almacenamiento privado, fuera de las copias de seguridad, y nunca la muestra en el diagnóstico.")

            Heading("Cómo obtenerla")
            Paragraph("Estos son los métodos que documenta el proyecto Gadgetbridge. La app no extrae la clave por ti.")
            Bullet(
                "Registros de Mi Fitness (sin root): Mi Fitness (antes Xiaomi Wear, paquete com.xiaomi.wearable) guarda archivos de " +
                    "registro en el almacenamiento del teléfono, en Android/data/com.xiaomi.wearable/files/log/ " +
                    "(en la variante Mi Health: Android/data/com.mi.health/files/log/). " +
                    "Busca en ellos (por ejemplo en XiaomiFit.device.log) los campos «encryptKey», «authKey», «token» o " +
                    "«huamiAuthKey» seguidos de 32 caracteres hexadecimales. Si hay varios, prueba primero el más cercano al final del archivo. " +
                    "En Android 11 y posteriores esas carpetas suelen ser accesibles solo conectando el teléfono a un ordenador (MTP o adb).",
            )
            Bullet(
                "Teléfono con root: cierra Mi Fitness y consulta su base de datos «device_db»; la clave está en el atributo " +
                    "«auth_key» o «encrypt_key» del JSON «detail».",
            )
            Bullet(
                "Herramienta de terceros «Xiaomi Cloud Tokens Extractor»: inicia sesión con tu cuenta Xiaomi en un ordenador y " +
                    "muestra las claves de tus dispositivos. Es software de terceros: úsalo bajo tu responsabilidad.",
            )

            Heading("Introducirla")
            Paragraph("Pégala en el campo «Clave de autenticación», con o sin el prefijo 0x. Se ignoran espacios y saltos de línea.")

            Heading("Antes de conectar")
            Paragraph(
                "La pulsera solo admite una conexión. Si Mi Fitness está conectado a ella, detén Mi Fitness temporalmente: " +
                    "Ajustes → Aplicaciones → Mi Fitness → Forzar detención. Al terminar, abre Mi Fitness otra vez para que siga " +
                    "sincronizando con Health Connect. Esta app nunca cierra ni modifica Mi Fitness por su cuenta.",
            )

            Heading("Más información")
            TextButton(onClick = { runCatching { uri.openUri(GB_SERVER_PAIRING) } }) { Text("Gadgetbridge: obtener la clave (servidor Huami/Xiaomi)") }
            TextButton(onClick = { runCatching { uri.openUri(GB_XIAOMI_DEVICES) } }) { Text("Gadgetbridge: dispositivos Xiaomi") }
            TextButton(onClick = { runCatching { uri.openUri(CLOUD_TOKENS_EXTRACTOR) } }) { Text("Xiaomi Cloud Tokens Extractor (terceros)") }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium)
}
