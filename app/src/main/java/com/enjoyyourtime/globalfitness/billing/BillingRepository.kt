package com.enjoyyourtime.globalfitness.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.cloud.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

sealed interface ProductState {
    data object Loading : ProductState
    data class Available(val price: String) : ProductState
    data class Unavailable(val message: String) : ProductState
}

/** What the "Quitar anuncios" screen shows. */
data class PremiumUiState(
    val product: ProductState = ProductState.Loading,
    val ownership: Ownership = Ownership.UNKNOWN,
    val isPremium: Boolean = false,
    val simulated: Boolean = false,
    val busy: Boolean = false,
    /** Spanish feedback of the last action, if any. */
    val message: String? = null,
)

/**
 * One-time, non-consumable purchase "Quitar anuncios" (product `remove_ads`) with Google Play
 * Billing. Google Play is the source of truth: purchases are restored with queryPurchasesAsync on
 * start / resume, and the last answer is cached so offline users stay ad-free.
 */
class BillingRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val suppression: AdSuppression,
) : PurchasesUpdatedListener {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val connectMutex = Mutex()
    private val connectRetry = RetryPolicy(baseDelayMs = 1_000, maxDelayMs = 15_000, maxAttempts = 4)
    private val reduceLock = Any()
    private var productDetails: ProductDetails? = null

    private var entitlement = EntitlementState(
        cachedPremium = prefs.getBoolean(KEY_PREMIUM, false),
        simulated = BuildConfig.DEBUG && prefs.getBoolean(KEY_SIMULATED, false),
    )

    private val _isPremium = MutableStateFlow(entitlement.isPremium)

    /** Ads are shown only while this is false. */
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _ui = MutableStateFlow(PremiumUiState(isPremium = entitlement.isPremium, simulated = entitlement.simulated))
    val uiState: StateFlow<PremiumUiState> = _ui.asStateFlow()

    private val _entitlementKnown = MutableStateFlow(false)

    /**
     * True once Google Play answered (or could not be reached), or after [ENTITLEMENT_WAIT_MS]:
     * ads wait for it so a premium user on a new phone never sees one before the restore.
     */
    val entitlementKnown: StateFlow<Boolean> = _entitlementKnown.asStateFlow()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) refresh() },
        )
        scope.launch {
            delay(ENTITLEMENT_WAIT_MS)
            _entitlementKnown.value = true
        }
    }

    /** Price + purchases (restores on start / resume). */
    fun refresh() {
        scope.launch {
            if (!connect()) {
                _entitlementKnown.value = true
                return@launch
            }
            loadProduct()
            queryPurchases(userInitiated = false)
            _entitlementKnown.value = true
        }
    }

    /** "Restaurar compras". */
    fun restore() {
        scope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            if (connect()) queryPurchases(userInitiated = true)
            _ui.update { it.copy(busy = false) }
        }
    }

    /**
     * "Comprar". The Activity is only held weakly while Play is being prepared, so a screen closed
     * meanwhile is not leaked. The result arrives in [onPurchasesUpdated].
     */
    fun launchPurchase(activity: Activity) {
        val activityRef = WeakReference(activity)
        scope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            try {
                if (!connect()) return@launch
                val details = productDetails ?: loadProduct()
                if (details == null) {
                    _ui.update { it.copy(message = UNAVAILABLE_MESSAGE) }
                    return@launch
                }
                val offerToken = details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
                    ?: details.oneTimePurchaseOfferDetails?.offerToken
                val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .apply { if (offerToken != null) setOfferToken(offerToken) }
                    .build()
                val flowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build()
                val host = activityRef.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@launch
                suppression.set(AdSuppression.PURCHASE, true)
                val result = client.launchBillingFlow(host, flowParams)
                if (result.responseCode != BillingResponseCode.OK) {
                    suppression.set(AdSuppression.PURCHASE, false)
                    _ui.update { it.copy(message = BillingMessages.forCode(result.responseCode)) }
                }
            } catch (e: CancellationException) {
                suppression.set(AdSuppression.PURCHASE, false)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Purchase flow failed", e)
                suppression.set(AdSuppression.PURCHASE, false)
                _ui.update { it.copy(message = "No se pudo abrir la compra de Google Play. Inténtalo de nuevo.") }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** Debug builds only: lets the UI be tested without a Play purchase. */
    fun setSimulatedPremium(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        reduce(EntitlementEvent.SimulationChanged(enabled))
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        suppression.set(AdSuppression.PURCHASE, false)
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                handlePurchases(list, authoritative = false)
                val message = when (_ui.value.ownership) {
                    Ownership.OWNED -> "¡Gracias por tu apoyo! Ya no verás anuncios."
                    Ownership.PENDING -> PENDING_MESSAGE
                    else -> null
                }
                _ui.update { it.copy(message = message) }
            }
            BillingResponseCode.USER_CANCELED -> Unit
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _ui.update { it.copy(message = "Ya habías comprado «Quitar anuncios». Restaurando la compra…") }
                scope.launch { queryPurchases(userInitiated = false) }
            }
            else -> _ui.update { it.copy(message = BillingMessages.forCode(result.responseCode)) }
        }
    }

    private suspend fun loadProduct(): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(Entitlements.REMOVE_ADS)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull { it.productId == Entitlements.REMOVE_ADS }
        productDetails = details
        val price = details?.let { it.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: it.oneTimePurchaseOfferDetails }?.formattedPrice
        _ui.update {
            it.copy(
                product = when {
                    price != null -> ProductState.Available(price)
                    result.billingResult.responseCode != BillingResponseCode.OK ->
                        ProductState.Unavailable(BillingMessages.forCode(result.billingResult.responseCode))
                    else -> ProductState.Unavailable(UNAVAILABLE_MESSAGE)
                },
            )
        }
        return details
    }

    private suspend fun queryPurchases(userInitiated: Boolean) {
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            reduce(EntitlementEvent.QueryFailed)
            if (userInitiated) _ui.update { it.copy(message = BillingMessages.forCode(result.billingResult.responseCode)) }
            return
        }
        handlePurchases(result.purchasesList, authoritative = true)
        if (userInitiated) {
            val message = when (entitlement.ownership) {
                Ownership.OWNED -> "Compra restaurada: no verás anuncios."
                Ownership.PENDING -> PENDING_MESSAGE
                else -> "No hay ninguna compra de «Quitar anuncios» en esta cuenta de Google Play."
            }
            _ui.update { it.copy(message = message) }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>, authoritative: Boolean) {
        val infos = purchases.map { it.toInfo() }
        reduce(if (authoritative) EntitlementEvent.PurchasesQueried(infos) else EntitlementEvent.PurchasesUpdated(infos))
        for (purchase in purchases) {
            if (Entitlements.REMOVE_ADS in purchase.products &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                !purchase.isAcknowledged
            ) {
                scope.launch { acknowledge(purchase.purchaseToken) }
            }
        }
    }

    /** Unacknowledged purchases are refunded by Play after 3 days; failures are retried on the next refresh. */
    private suspend fun acknowledge(token: String) {
        try {
            val result = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build())
            if (result.responseCode == BillingResponseCode.OK) reduce(EntitlementEvent.Acknowledged)
            else Log.w(TAG, "Acknowledge failed: ${result.responseCode} ${result.debugMessage}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Acknowledge failed", e)
        }
    }

    /** Serialized: Play callbacks and coroutines may report purchases concurrently. */
    private fun reduce(event: EntitlementEvent) = synchronized(reduceLock) {
        val next = Entitlements.reduce(entitlement, event)
        entitlement = next
        prefs.edit {
            putBoolean(KEY_PREMIUM, next.cachedPremium)
            putBoolean(KEY_SIMULATED, next.simulated)
        }
        _isPremium.value = next.isPremium
        _ui.update { it.copy(ownership = next.ownership, isPremium = next.isPremium, simulated = next.simulated) }
    }

    /** Connects to Play with retry/backoff. Reports a Spanish message in the UI state when it gives up. */
    private suspend fun connect(): Boolean = connectMutex.withLock {
        if (client.isReady) return@withLock true
        var attempts = 0
        var result = startConnection()
        while (result.responseCode != BillingResponseCode.OK) {
            attempts++
            val retryable = result.responseCode != BillingResponseCode.BILLING_UNAVAILABLE &&
                result.responseCode != BillingResponseCode.FEATURE_NOT_SUPPORTED
            if (!retryable || !connectRetry.canRetry(attempts)) {
                Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
                val message = BillingMessages.forCode(result.responseCode)
                _ui.update {
                    it.copy(
                        product = if (it.product is ProductState.Available) it.product else ProductState.Unavailable(message),
                        message = if (it.busy) message else it.message,
                    )
                }
                return@withLock false
            }
            delay(connectRetry.delayFor(attempts))
            result = startConnection()
        }
        true
    }

    private suspend fun startConnection(): BillingResult = suspendCancellableCoroutine { cont ->
        val resumed = AtomicBoolean(false)
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (resumed.compareAndSet(false, true)) cont.resume(result)
                }

                override fun onBillingServiceDisconnected() {
                    // Automatic service reconnection is enabled; the next call reconnects.
                    if (resumed.compareAndSet(false, true)) {
                        cont.resume(BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build())
                    }
                }
            },
        )
    }

    private fun Purchase.toInfo() = PurchaseInfo(
        products = products,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseInfo.State.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseInfo.State.PENDING
            else -> PurchaseInfo.State.OTHER
        },
        acknowledged = isAcknowledged,
    )

    private companion object {
        const val TAG = "Billing"
        const val PREFS = "billing_cache"
        const val KEY_PREMIUM = "remove_ads_owned"
        const val KEY_SIMULATED = "debug_simulated_premium"
        const val UNAVAILABLE_MESSAGE = "«Quitar anuncios» no está disponible ahora mismo en Google Play."
        const val PENDING_MESSAGE =
            "Pago pendiente. Los anuncios desaparecerán en cuanto Google Play confirme el pago."

        /** Longest wait for Google Play before ads may be shown to a user not known to be premium. */
        const val ENTITLEMENT_WAIT_MS = 5_000L
    }
}

/** Spanish messages for Play Billing response codes. */
internal object BillingMessages {
    fun forCode(code: Int): String = when (code) {
        BillingResponseCode.BILLING_UNAVAILABLE ->
            "Google Play no permite compras en este dispositivo o con esta cuenta."
        BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.NETWORK_ERROR,
        BillingResponseCode.SERVICE_DISCONNECTED ->
            "No se pudo conectar con Google Play. Comprueba la conexión e inténtalo de nuevo."
        BillingResponseCode.ITEM_UNAVAILABLE -> "«Quitar anuncios» no está disponible ahora mismo en Google Play."
        BillingResponseCode.DEVELOPER_ERROR ->
            "Google Play ha rechazado la compra. Instala la app desde Google Play para poder comprar."
        BillingResponseCode.FEATURE_NOT_SUPPORTED -> "Tu versión de Google Play no admite esta compra."
        BillingResponseCode.ITEM_ALREADY_OWNED -> "Ya tienes «Quitar anuncios»."
        else -> "Error de Google Play ($code). Inténtalo de nuevo."
    }
}
