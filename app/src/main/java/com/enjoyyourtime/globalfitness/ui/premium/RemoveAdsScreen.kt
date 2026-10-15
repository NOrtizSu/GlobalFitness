@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.premium

import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.billing.Ownership
import com.enjoyyourtime.globalfitness.billing.ProductState
import com.enjoyyourtime.globalfitness.ui.account.SuppressInterstitials

/** One-time purchase "Quitar anuncios". No ads are shown on this screen. */
@Composable
fun RemoveAdsScreen(onBack: () -> Unit) {
    val billing = (LocalContext.current.applicationContext as GlobalFitnessApp).container.billing
    val state by billing.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    SuppressInterstitials(AdSuppression.PREMIUM_SCREEN)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quitar anuncios") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("GlobalFitness sin anuncios", style = MaterialTheme.typography.headlineSmall)
            Benefit("Sin banners ni anuncios a pantalla completa.")
            Benefit("Pago único: sin suscripción.")
            Benefit("Se restaura en tus otros móviles con la misma cuenta de Google Play.")
            Benefit("Ayudas a mantener y mejorar la app.")

            val ownedForReal = state.ownership == Ownership.OWNED
            when {
                ownedForReal -> StatusCard("Compra activa: no verás anuncios. ¡Gracias por tu apoyo!")
                state.ownership == Ownership.PENDING -> StatusCard(
                    "Pago pendiente. Los anuncios desaparecerán en cuanto Google Play confirme el pago.",
                )
                else -> PriceLine(state.product)
            }

            val price = (state.product as? ProductState.Available)?.price
            if (!ownedForReal) {
                Button(
                    onClick = { activity?.let(billing::launchPurchase) },
                    enabled = price != null && !state.busy && state.ownership != Ownership.PENDING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (price != null) "Comprar por $price" else "Comprar")
                }
            }
            OutlinedButton(onClick = billing::restore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Restaurar compras")
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (BuildConfig.DEBUG) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Simular premium", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Solo en compilaciones de depuración: oculta los anuncios sin comprar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.simulated, onCheckedChange = billing::setSimulatedPremium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Benefit(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusCard(text: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun PriceLine(product: ProductState) {
    when (product) {
        ProductState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Consultando el precio en Google Play…", style = MaterialTheme.typography.bodyMedium)
        }
        is ProductState.Available -> Text(product.price, style = MaterialTheme.typography.headlineMedium)
        is ProductState.Unavailable -> Text(
            product.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
