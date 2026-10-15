package com.enjoyyourtime.globalfitness

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

    override fun onStart() {
        super.onStart()
        // The 24-hour clock setting may have changed while the app was in the background.
        Fmt.init(this)
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
