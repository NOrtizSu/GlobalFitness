package com.enjoyyourtime.globalfitness.ui.band

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.GlobalFitnessTheme

/**
 * Hosts the experimental direct band screen. Being visible is what keeps the on-demand connection
 * alive: leaving it releases the band for Mi Fitness after a short grace period.
 */
class BandActivity : ComponentActivity() {
    private val viewModel: BandViewModel by viewModels { BandViewModel.Factory }

    override fun onStart() {
        super.onStart()
        Fmt.init(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.onVisible()
                    Lifecycle.Event.ON_STOP -> viewModel.onHidden()
                    else -> Unit
                }
            },
        )
        setContent {
            GlobalFitnessTheme {
                BandScreen(viewModel = viewModel, onBack = ::finish)
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, BandActivity::class.java)
    }
}
