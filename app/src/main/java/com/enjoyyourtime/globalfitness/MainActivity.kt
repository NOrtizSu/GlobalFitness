package com.enjoyyourtime.globalfitness

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.enjoyyourtime.globalfitness.ui.AppRoot
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.GlobalFitnessTheme

class MainActivity : ComponentActivity() {

    /** The UI is Spanish-only: force es-ES so Material components (date picker...) match. */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply { setLocale(Fmt.LOCALE) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val scanner = (application as GlobalFitnessApp).container.scanner
        // Scan only while the app is visible.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> scanner.onForegroundStart()
                    Lifecycle.Event.ON_STOP -> scanner.onForegroundStop()
                    else -> Unit
                }
            },
        )

        setContent {
            GlobalFitnessTheme {
                AppRoot()
            }
        }
    }
}
