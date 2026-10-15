package com.enjoyyourtime.globalfitness.ui.health

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.GlobalFitnessTheme

/** Hosts the Health Connect screen until the navigation redesign wires it into the main UI. */
class HealthActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply { setLocale(Fmt.LOCALE) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlobalFitnessTheme {
                HealthScreen(onBack = ::finish)
            }
        }
    }
}
