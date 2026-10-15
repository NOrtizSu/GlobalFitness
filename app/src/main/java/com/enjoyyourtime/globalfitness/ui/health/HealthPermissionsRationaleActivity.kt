@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.health

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.GlobalFitnessTheme

/**
 * Privacy policy / permission rationale shown by Health Connect ("¿Por qué pide estos
 * permisos?") on Android 13 and lower, and via the VIEW_PERMISSION_USAGE alias on Android 14+.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply { setLocale(Fmt.LOCALE) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlobalFitnessTheme {
                RationaleScreen(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun RationaleScreen(onClose: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Privacidad de los datos de salud") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Paragraph("GlobalFitness usa Health Connect para dos cosas:")
            Paragraph(
                "• Leer los datos de tu pulsera o reloj (por ejemplo, la Xiaomi Smart Band a través de Mi Fitness): " +
                    "pasos, distancia, calorías, pulso, pulso en reposo, variabilidad del pulso, sueño, oxígeno en " +
                    "sangre, frecuencia respiratoria y entrenamientos. También lee el peso y la grasa corporal que " +
                    "registren otras apps. Con el permiso de datos anteriores importa hasta un año.",
            )
            Paragraph(
                "• Escribir las mediciones de tu báscula: peso, grasa corporal, masa ósea, masa magra, metabolismo " +
                    "basal y agua corporal. Si borras una medición en la app, también se borra de Health Connect.",
            )
            Paragraph(
                "Los datos leídos de Health Connect se guardan solo en este teléfono: no se suben a la copia en la " +
                    "nube opcional, no se usan para publicidad y no se comparten con nadie. La app los usa " +
                    "únicamente para mostrarte resúmenes y gráficos.",
            )
            Paragraph(
                "Puedes retirar cualquier permiso cuando quieras desde Health Connect; la app deja de leer ese tipo " +
                    "de dato y borra su copia local en la siguiente sincronización.",
            )
            Button(onClick = onClose) { Text("Entendido") }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
